package engine.transaction.dedicated.ordered;

import applications.util.OsUtils;
import engine.common.Operation;
import engine.content.T_StreamContent;
import engine.profiler.Metrics;
import engine.storage.SchemaRecord;
import engine.storage.datatype.DataBox;
import engine.storage.datatype.DoubleDataBox;
import engine.storage.datatype.IntDataBox;
import engine.storage.datatype.ListDoubleDataBox;
import engine.transaction.function.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.SOURCE_CONTROL;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static applications.CONTROL.*;
import static applications.constants.PositionKeepingConstants.Constant.MOVING_AVERAGE_WINDOW;
import static applications.constants.PositionKeepingConstants.Constant.SIZE_VALUE;
import static engine.Meta.MetaTypes.AccessType.*;
import static engine.profiler.Metrics.MeasureTools.*;

/**
 * There is one TxnProcessingEngine of each stage.
 * This is closely bundled with the start_ready map.
 * <p>
 * It now only works for single stage..
 */
public final class TxnProcessingEngine {
    private static final Logger LOG = LoggerFactory.getLogger(TxnProcessingEngine.class);
    private static TxnProcessingEngine instance = new TxnProcessingEngine();
    Metrics metrics;

    private Integer num_op = -1;
    private Integer first_exe;
    private Integer last_exe;
    private CyclicBarrier barrier;
    private Instance standalone_engine;
    private ConcurrentHashMap<String, Holder_in_range> holder_by_stage;//multi table support.
    private int app;

    private int TOTAL_CORES;
    private long previous_ID = 0;

    private TxnProcessingEngine() {
        OsUtils.configLOG(LOG);
    }

    //    fast determine the corresponding instance. This design is for NUMA-awareness.
    private HashMap<Integer, Instance> multi_engine = new HashMap<>();//one island one engine.
//    private int partition = 1;//NUMA-awareness. Hardware Island. If it is one, it is the default shared-everything.
//    private int range_min = 0;
//    private int range_max = 1_000_000;//change this for different application.

    /**
     * @return
     */
    public static TxnProcessingEngine getInstance() {
        return instance;
    }

    public void initilize(int size, int app) {
        num_op = size;
        this.app = app;
//        holder_by_stage = new Holder_in_range(num_op);
        holder_by_stage = new ConcurrentHashMap<>();


        //make it flexible later.
        if (app == 1)//SL
        {
            holder_by_stage.put("accounts", new Holder_in_range(num_op));
            holder_by_stage.put("bookEntries", new Holder_in_range(num_op));
        } else if (app == 2) {//OB
            holder_by_stage.put("goods", new Holder_in_range(num_op));
        } else if (app == 3) {//TP
            holder_by_stage.put("segment_speed", new Holder_in_range(num_op));
            holder_by_stage.put("segment_cnt", new Holder_in_range(num_op));
        } else {//MB
            holder_by_stage.put("MicroTable", new Holder_in_range(num_op));
        }

        metrics = Metrics.getInstance();
    }

    public Holder_in_range getHolder(String table_name) {
        return holder_by_stage.get(table_name);
    }

    public void engine_init(Integer first_exe, Integer last_exe, Integer stage_size, int tp) {
        this.first_exe = first_exe;
        this.last_exe = last_exe;
        num_op = stage_size;
        barrier = new CyclicBarrier(stage_size);

        if (enable_work_partition) {
            if (island == -1) {//one engine one core.
                for (int i = 0; i < tp; i++)
                    multi_engine.put(i, new Instance(1));
            } else if (island == -2) {//one engine one socket.

                int actual_island = tp / CORE_PER_SOCKET;
                int i;
                for (i = 0; i < actual_island; i++) {
                    multi_engine.put(i, new Instance(CORE_PER_SOCKET));
                }

                if (tp % CORE_PER_SOCKET != 0) {
                    multi_engine.put(i, new Instance(tp % CORE_PER_SOCKET));
                }


            } else
                throw new UnsupportedOperationException();

//            if (island != -1)
//                for (int i = 0; i < island; i++) {
//                    multi_engine.put(i, new Instance(tp / island));
//                }
//            else {
//
//            }
        } else {
            //single box engine.
            standalone_engine = new Instance(tp);

        }
        TOTAL_CORES = tp;
        LOG.info("Engine initialize:" + " Working Threads:" + tp);
    }


    public void engine_shutdown() {
        LOG.info("Shutdown Engine!");
        if (enable_work_partition) {
            for (Instance engine : multi_engine.values()) {
                engine.close();
            }
        } else {
            //single box engine.
            standalone_engine.close();
        }
    }

    private void CT_Transfer_Fun(Operation operation, long previous_mark_ID, boolean clean) {

        // read
        SchemaRecord preValues = operation.condition_records[0].content_.readPreValues(operation.bid);
        SchemaRecord preValues1 = operation.condition_records[1].content_.readPreValues(operation.bid);

        if (preValues == null) {
            LOG.info("Failed to read condition records[0]" + operation.condition_records[0].record_.GetPrimaryKey());
            LOG.info("Its version size:" + ((T_StreamContent) operation.condition_records[0].content_).versions.size());
            for (Map.Entry<Long, SchemaRecord> schemaRecord : ((T_StreamContent) operation.condition_records[0].content_).versions.entrySet()) {
                LOG.info("Its contents:" + schemaRecord.getKey() + " value:" + schemaRecord.getValue() + " current bid:" + operation.bid);
            }
            LOG.info("TRY reading:" + ((T_StreamContent) operation.condition_records[0].content_).readPreValues(operation.bid));//not modified in last round);
        }
        if (preValues1 == null) {
            LOG.info("Failed to read condition records[1]" + operation.condition_records[1].record_.GetPrimaryKey());
            LOG.info("Its version size:" + ((T_StreamContent) operation.condition_records[1].content_).versions.size());
            for (Map.Entry<Long, SchemaRecord> schemaRecord : ((T_StreamContent) operation.condition_records[1].content_).versions.entrySet()) {
                LOG.info("Its contents:" + schemaRecord.getKey() + " value:" + schemaRecord.getValue() + " current bid:" + operation.bid);
            }
            LOG.info("TRY reading:" + ((T_StreamContent) operation.condition_records[1].content_).versions.get(operation.bid));//not modified in last round);
        }

        final long sourceAccountBalance = preValues.getValues().get(1).getLong();
        final long sourceAssetValue = preValues1.getValues().get(1).getLong();

        //when d_record is different from condition record
        //It may generate cross-records dependency problem.
        //Fix it later.

        // check the preconditions
        //TODO: make the condition checking more generic in future.
        if (sourceAccountBalance > operation.condition.arg1
                && sourceAccountBalance > operation.condition.arg2
                && sourceAssetValue > operation.condition.arg3) {

            //read
            SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
            List<DataBox> values = srcRecord.getValues();

            SchemaRecord tempo_record;
            tempo_record = new SchemaRecord(values);//tempo record

            //apply function.
            if (operation.function instanceof INC) {
                tempo_record.getValues().get(1).incLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else if (operation.function instanceof DEC) {
                tempo_record.getValues().get(1).decLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else
                throw new UnsupportedOperationException();

            operation.d_record.content_.WriteAccess(operation.bid, previous_mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
            //Operation.d_record.content_.WriteAccess(Operation.bid, new SchemaRecord(values), wid);//does this even needed?
            operation.success[0] = true;
//            if (operation.table_name.equalsIgnoreCase("accounts") && operation.d_record.record_.GetPrimaryKey().equalsIgnoreCase("11")) {
//            LOG.info("key: " + operation.d_record.record_.GetPrimaryKey() + " BID: " + operation.bid + " set " + operation.success.hashCode() + " to true." + " sourceAccountBalance:" + sourceAccountBalance);
//            }
        } else {
//            if (operation.success[0] == true)
//                System.nanoTime();
            operation.success[0] = false;
        }
    }

    private void CT_Depo_Fun(Operation operation, long mark_ID, boolean clean) {
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        List<DataBox> values = srcRecord.getValues();
        //apply function to modify..
        SchemaRecord tempo_record;
        tempo_record = new SchemaRecord(values);//tempo record
        tempo_record.getValues().get(operation.column_id).incLong(operation.function.delta_long);//compute.
        operation.s_record.content_.WriteAccess(operation.bid, mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
    }

    private void process(Operation operation, long mark_ID, boolean clean) {

        if (operation.accessType == READ_ONLY) {//used in MB.
//            operation.record_ref.inc(Thread.currentThread().getName());

            //read source.
//            List<DataBox> dstRecord = operation.d_record.content_.ReadAccess(operation.bid, operation.accessType).getValues();

            SchemaRecord schemaRecord = operation.d_record.content_.ReadAccess(operation.bid, mark_ID, clean, operation.accessType);

            operation.record_ref.setRecord(new SchemaRecord(schemaRecord.getValues()));//Note that, locking scheme allows directly modifying on original table d_record.

            if (enable_debug)
                if (operation.record_ref.cnt == 0) {
                    System.out.println("Not assigning");
                    System.exit(-1);
                }

        } else if (operation.accessType == WRITE_ONLY) {//push evaluation down. //used in MB.

            if (operation.value_list != null) { //directly replace value_list --only used for MB.

                //read source.
//                List<DataBox> dstRecord = operation.d_record.content_.ReadAccess(operation.bid, operation.accessType).getValues();


//                if (enable_mvcc) {
//
//                }else {
//                operation.d_record.record_.updateValues(operation.value_list);
//                }

//                operation.d_record.record_.s.get(1).setString(values.get(1).getString(), VALUE_LEN);

                operation.d_record.content_.WriteAccess(operation.bid, mark_ID, clean, new SchemaRecord(operation.value_list));//it may reduce NUMA-traffic.

            } else { //update by column_id.
                operation.d_record.record_.getValues().get(operation.column_id).setLong(operation.value);
//                LOG.info("Alert price:" + operation.value);
            }
        } else if (operation.accessType == READ_WRITE) {//read, modify, write.

            if (app == 1) {
                CT_Depo_Fun(operation, mark_ID, clean);//used in SL
            } else {
                SchemaRecord srcRecord = operation.s_record.content_.ReadAccess(operation.bid, mark_ID, clean, operation.accessType);
                List<DataBox> values = srcRecord.getValues();

                //apply function to modify..
                if (operation.function instanceof INC) {
                    values.get(operation.column_id).setLong(values.get(operation.column_id).getLong() + operation.function.delta_long);
                } else
                    throw new UnsupportedOperationException();
            }

        } else if (operation.accessType == READ_WRITE_COND) {//read, modify (depends on condition), write( depends on condition).
            //TODO: pass function here in future instead of hard-code it. Seems not trivial in Java, consider callable interface?

            if (app == 1) {//used in SL
                CT_Transfer_Fun(operation, mark_ID, clean);
            } else if (app == 2) {//used in OB
                //check if any item is not able to buy.
                List<DataBox> d_record = operation.condition_records[0].content_
                        .ReadAccess(operation.bid, mark_ID, clean, operation.accessType).getValues();

                long askPrice = d_record.get(1).getLong();//price
                long left_qty = d_record.get(2).getLong();//available qty;
                long bidPrice = operation.condition.arg1;
                long bid_qty = operation.condition.arg2;

                // check the preconditions
                if (bidPrice < askPrice || bid_qty > left_qty) {
                    operation.success[0] = false;
                } else {
                    d_record.get(2).setLong(left_qty - operation.function.delta_long);//new quantity.
                    operation.success[0] = true;
                }
            }

        } else if (operation.accessType == READ_WRITE_COND_READ) {
            assert operation.record_ref != null;
            if (app == 1) {//used in SL
                CT_Transfer_Fun(operation, mark_ID, clean);
                operation.record_ref.setRecord(operation.d_record.content_.readValues(operation.bid, mark_ID, clean));//read the resulting tuple.
            } else
                throw new UnsupportedOperationException();

            if (operation.record_ref.cnt == 0) {
                System.out.println("Not assigning");
                System.exit(-1);
            }
        } else if (operation.accessType == READ_WRITE_READ) {//used in PK, TP.
            assert operation.record_ref != null;

            //read source.
            List<DataBox> srcRecord = operation.s_record.content_.ReadAccess(operation.bid, mark_ID, clean, operation.accessType).getValues();

            //apply function.
            if (operation.function instanceof Mean) {

                // compute.
                ListDoubleDataBox valueList = (ListDoubleDataBox) srcRecord.get(1);
                double sum = srcRecord.get(2).getDouble();
                double[] nextDouble = operation.function.new_value;

                for (int j = 0; j < SIZE_VALUE; j++) {
                    sum -= valueList.addItem(nextDouble[j]);
                    sum += nextDouble[j];
                }

                // update content.
                srcRecord.get(2).setDouble(sum);
                // Operation.d_record.content_.WriteAccess(Operation.bid, new SchemaRecord(srcRecord), wid);//not even needed.

                // configure return-record.
                if (valueList.size() < MOVING_AVERAGE_WINDOW) {//just added
                    operation.record_ref.setRecord(new SchemaRecord(new DoubleDataBox(nextDouble[SIZE_VALUE - 1])));
                } else {
                    operation.record_ref.setRecord(new SchemaRecord(new DoubleDataBox(sum / MOVING_AVERAGE_WINDOW)));
                }
//                            LOG.info("BID:" + Operation.bid + " is set @" + DateTime.now());
            } else if (operation.function instanceof AVG) {//used by TP
                //                double lav = (latestAvgSpeeds + speed) / 2;//compute the average.

                double latestAvgSpeeds = srcRecord.get(1).getDouble();
                double lav;
                if (latestAvgSpeeds == 0) {//not initialized
                    lav = operation.function.delta_double;
                } else
                    lav = (latestAvgSpeeds + operation.function.delta_double) / 2;

                srcRecord.get(1).setDouble(lav);//write to state.
                operation.record_ref.setRecord(new SchemaRecord(new DoubleDataBox(lav)));//return updated record.


            } else if (operation.function instanceof CNT) {//used by TP
                HashSet cnt_segment = srcRecord.get(1).getHashSet();
                cnt_segment.add(operation.function.delta_int);//update hashset; updated state also. TODO: be careful of this.
                operation.record_ref.setRecord(new SchemaRecord(new IntDataBox(cnt_segment.size())));//return updated record.
            } else
                throw new UnsupportedOperationException();
        }

    }


    //TODO: actual evaluation on the operation_chain.
    private void process(MyList<Operation> operation_chain, long mark_ID) {
        while (true) {
            Operation operation = operation_chain.pollFirst();//multiple threads may work on the same operation chain, use MVCC to preserve the correctness.
            if (operation == null) return;

            if (operation_chain.size() == 0)// TODO: THIS ONLY WORKS FOR GS because there's no cross-state access.
                process(operation, mark_ID, true);
            else
                process(operation, mark_ID, false);

        }//loop.

//        if (enable_work_stealing) {
//            while (true) {
//                Operation operation = operation_chain.pollFirst();//multiple threads may work on the same operation chain, use MVCC to preserve the correctness.
//                if (operation == null) return;
//                process(operation);
////                operation.set_worker(Thread.currentThread().getName());
//            }//loop.
//        } else {
////            if (operation_chain.getTable_name().equalsIgnoreCase("accounts") && operation_chain.getPrimaryKey().equalsIgnoreCase("11")) {
////                System.nanoTime();
////            }
//            for (Operation operation : operation_chain) {
//                process(operation);
////                if (operation_chain.getTable_name().equalsIgnoreCase("accounts") && operation_chain.getPrimaryKey().equalsIgnoreCase("11"))
////                    LOG.info("finished process bid:" + operation.bid + " by " + Thread.currentThread().getName());
//            }//loop.
////            operation_chain.clear();
//
//        }
    }


//    private int ThreadToSocket(int thread_Id) {
//
//        return (thread_Id + 2) % CORE_PER_SOCKET;
//    }

    private int ThreadToEngine(int thread_Id) {
        int rt;

        if (island == -1) {
            rt = (thread_Id);
        } else if (island == -2) {
            rt = thread_Id / CORE_PER_SOCKET;
        } else
            throw new UnsupportedOperationException();

//        if (island != -1)
//            rt = (thread_Id) / (TOTAL_CORES / island);
//        else
//            rt = (thread_Id);
        // LOG.debug("submit to engine: "+ rt);
        return rt;
    }

    private int TaskToEngine(int key) {
        int rt;

        if (island == -1) {
            rt = (key);
        } else if (island == -2) {
            rt = key / CORE_PER_SOCKET;
        } else
            throw new UnsupportedOperationException();

//        if (island != -1)
//            rt = (thread_Id) / (TOTAL_CORES / island);
//        else
//            rt = (thread_Id);
        // LOG.debug("submit to engine: "+ rt);
        return rt;
    }


    private int submit_task(int thread_Id, Holder holder, Collection<Task> callables, long mark_ID) {

        int sum = 0;

//        Instance instance = standalone_engine;//multi_engine.get(key);
//        assert instance != null;
//        for (Map.Entry<Range<Integer>, Holder> rangeHolderEntry : holder.entrySet()) {
//            Holder operation_chain = rangeHolderEntry.getValue();

        for (MyList<Operation> operation_chain : holder.holder_v1.values()) {
//        for (int i = 0; i < H2_SIZE; i++) {
//            ConcurrentSkipListSet<Operation> operation_chain = holder.holder_v2[i];
//        Set<Operation> operation_chain = holder.holder_v3;
            if (operation_chain.size() > 0) {
                if (enable_profile)
                    sum += operation_chain.size();

//                Instance instance = standalone_engine;//multi_engine.get(key);
                if (!Thread.currentThread().isInterrupted()) {
                    if (enable_engine) {
                        Task task = new Task(operation_chain, mark_ID);

                        if (enable_debug)
                            LOG.trace("Submit operation_chain:" + OsUtils.Addresser.addressOf(operation_chain) + " with size:" + operation_chain.size());

                        if (enable_work_partition) {

//                            String key = operation_chain.getPrimaryKey();
//                            int p = key_to_partition(key); p == thread_id.

                            multi_engine.get(TaskToEngine(thread_Id)).executor.submit(task);
                        } else {
                            standalone_engine.executor.submit(task);
                        }
                        callables.add(task);
                    } else {
                        process(operation_chain, mark_ID);//directly apply the computation.
                    }
                }
            }
        }


        return sum;
    }


    private int evaluation(int thread_Id, long mark_ID) throws InterruptedException {

        BEGIN_TP_SUBMIT_TIME_MEASURE(thread_Id);

        //LOG.DEBUG(thread_Id + "\tall source marked checkpoint, starts TP evaluation for watermark bid\t" + bid);

        Collection<Task> callables = new Vector<>();

        int task = 0;

        for (Holder_in_range holder_in_range : holder_by_stage.values()) {
            Holder holder = holder_in_range.rangeMap.get(thread_Id);
            task += submit_task(thread_Id, holder, callables, mark_ID);
        }

        END_TP_SUBMIT_TIME_MEASURE(thread_Id, task);

        if (enable_debug)
            LOG.info("submit task:" + task);

        if (enable_engine) {
            if (enable_work_partition) {
                multi_engine.get(ThreadToEngine(thread_Id)).executor.invokeAll(callables);
            } else
                standalone_engine.executor.invokeAll(callables);
        }

        //blocking sync_ratio for all operation_chain to complete.
        //TODO: For now, we don't know the relationship between operation_chain and transaction, otherwise, we can asynchronously return.
//        for (Holder_in_range holder_in_range : holder_by_stage.values())
//            holder_in_range.rangeMap.clear();

//        callables.clear();

        return task;
    }


    /**
     * @param thread_Id
     * @param mark_ID
     * @return time spend in tp evaluation.
     * @throws InterruptedException
     */
    public void start_evaluation(int thread_Id, long mark_ID) throws InterruptedException {


        //It first needs to make sure checkpoints from all producers are received.
//        if (!enable_app_combo)//otherwise, already synchronized at spout side.
//            barrier.await();
//        else
        SOURCE_CONTROL.getInstance().Wait_Start(thread_Id);//sync for all threads to come to this line to ensure chains are constructed for the current batch.

        BEGIN_TP_CORE_TIME_MEASURE(thread_Id);

        int size = evaluation(thread_Id, previous_ID - kMaxThreadNum);


//        SOURCE_CONTROL.getInstance().CLEARWM();//sync_ratio for all threads to come to this line.


        END_TP_CORE_TIME_MEASURE_TS(thread_Id, size);//exclude task submission and synchronization time.

//        SOURCE_CONTROL.getInstance().Wait_Start();//no sync here. sync later.

        previous_ID = mark_ID;

        SOURCE_CONTROL.getInstance().Wait_End(thread_Id);//sync for all threads to come to this line.

    }

    /**
     * There shall be $num_op$ Holders.
     */
    public class Holder {
        //version 1: single list Operation on one key
        //	ConcurrentSkipListSet holder_v1 = new ConcurrentSkipListSet();
        public ConcurrentHashMap<String, MyList<Operation>> holder_v1 = new ConcurrentHashMap<>();
//        public ConcurrentSkipListSet<Operation>[] holder_v2 = new ConcurrentSkipListSet[H2_SIZE];

    }

    public class Holder_in_range {
        public ConcurrentHashMap<Integer, Holder> rangeMap = new ConcurrentHashMap<>();//each op has a holder.

        public Holder_in_range(Integer num_op) {
            int i;
            for (i = 0; i < num_op; i++) {
                rangeMap.put(i, new Holder());
            }
        }
    }

    /**
     * TP-processing instance.
     * If it is one, it is a shared everything configuration.
     * Otherwise, we use hash partition to distribute the data and workload.
     */
    class Instance implements Closeable {
        public ExecutorService executor;
        int range_min;
        int range_max;
//        ExecutorCompletionService<Integer> TP_THREADS; // In the next work, we can try asynchronous return from the TP-Layer!

        public Instance(int tpInstance, int range_min, int range_max) {
            this.range_min = range_min;
            this.range_max = range_max;


            if (enable_work_partition) {
                if (island == -1) {//one core one engine. there's no meaning of stealing.
                    executor = Executors.newSingleThreadExecutor();//one core one engine.
                } else if (island == -2) {//one socket one engine.

                    if (enable_work_stealing) {
                        executor = Executors.newWorkStealingPool(tpInstance);//shared, stealing.
                    } else
                        executor = Executors.newFixedThreadPool(tpInstance);//shared, no stealing.
                } else
                    throw new UnsupportedOperationException();//TODO: support more in future.
            } else {
                if (enable_work_stealing) {
                    executor = Executors.newWorkStealingPool(tpInstance);//shared, stealing.
                } else
                    executor = Executors.newFixedThreadPool(tpInstance);//shared, no stealing.
            }
        }

        /**
         * @param tpInstance
         */
        public Instance(int tpInstance) {

            this(tpInstance, 0, 0);
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }
//
//    class dummyTask implements Callable<Integer> {
//        int socket;
//
//        public dummyTask(int socket) {
//            this.socket = socket;
//        }
//
//        @Override
//        public Integer call() {
//            runOnNode(socket);
//            return null;
//        }
//    }


    //the smallest unit of Task in TP.
    //Every Task will be assigned with one operation chain.
    class Task implements Callable<Integer> {
        private AtomicBoolean under_process;
        private final Set<Operation> operation_chain;
        private final long mark_ID;
//        private int socket;
//        private final long wid = 0;//watermark id.
//        private boolean un_processed = true;

//        public Task(Set<Operation> operation_chain, int socket) {
//
//            this.operation_chain = operation_chain;
////            wid = bid;
////            this.socket = socket;
//            if (!enable_work_stealing) {
//                under_process = new AtomicBoolean(false);
//            }
//        }

        public Task(Set<Operation> operation_chain, long mark_ID) {

            this.operation_chain = operation_chain;
            this.mark_ID = mark_ID;

            if (!(enable_work_stealing || island == -1)) {
                under_process = new AtomicBoolean(false);
            }

        }

        /**
         * TODO: improve this function later, so far, no application requires this...
         *
         * @param operation
         */
        private void wait_for_source(Operation operation) {
//            ConcurrentSkipListSet<multi_engine.common.Operation> source_operation_chain = holder_v1.get(Operation.s_record.record_.GetPrimaryKey());
//            //if the source_operation_chain is completely evaluated, we are safe to proceed. This is the most naive approach.
//            while (!source_operation_chain.isEmpty()) {
//                //Think about better shortcut.
//            }
        }

        //evaluate the operation_chain
        //TODO: paralleling this process.
        @Override
        public Integer call() {

            if (enable_work_stealing || island == -1) {// if island is not -1, it may cooperatively work on the same chain, use mvcc to ensure correctness.
                if (operation_chain.size() == 0) {
//                    if (enable_debug)
//                        LOG.info("RE-ENTRY "
//                                + "\t working on task:" + OsUtils.Addresser.addressOf(this) +
//                                " by:" + Thread.currentThread().getName());
                    return 0;
                }
                try {
                    process((MyList<Operation>) operation_chain, mark_ID);

                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
                return 0;
            } else {
                if (this.under_process.compareAndSet(false, true)) {//ensure one task is processed only once.
                    if (operation_chain.size() == 0) {
//                        if (enable_debug)
//                            LOG.info("RE-ENTRY "
//                                    + "\t working on task:" + OsUtils.Addresser.addressOf(this) +
//                                    " by:" + Thread.currentThread().getName());
                        return 0;
                    }

//                int i = 0;
//                if (enable_debug)
//                    LOG.info("Thread:\t" + Thread.currentThread().getName()
//                            + "\t working on task:" + OsUtils.Addresser.addressOf(this)
//                            + " with size of:" + operation_chain.size());

                    process((MyList<Operation>) operation_chain, mark_ID);
                    if (enable_debug)
                        LOG.trace("Thread:\t" + Thread.currentThread().getName()
                                + "reset task:" + OsUtils.Addresser.addressOf(this));

                    operation_chain.clear();
                    this.under_process.set(false);//reset
                    return 0;
                }
//            if (enable_debug)
//                LOG.info("Thread:\t" + Thread.currentThread().getName()
//                        + "\t exit on task:" + OsUtils.Addresser.addressOf(this)
//                        + " with size of:" + operation_chain.size());
//            while (!this.under_process.compareAndSet(false, true)) ;
                return 0;
            }
        }

    }

}