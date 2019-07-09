package applications.bolts.tp;

import applications.datatype.TollNotification;
import applications.datatype.util.AvgValue;
import applications.datatype.util.SegmentIdentifier;
import applications.param.txn.lr.LREvent;
import brisk.components.operators.api.TransactionalBolt;
import brisk.execution.runtime.tuple.impl.Tuple;
import brisk.execution.runtime.tuple.impl.msgs.GeneralMsg;
import engine.DatabaseException;
import engine.storage.datatype.DataBox;
import engine.transaction.impl.TxnContext;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static applications.CONTROL.enable_app_combo;
import static applications.CONTROL.enable_latency_measurement;
import static applications.Constants.DEFAULT_STREAM_ID;
import static engine.Meta.MetaTypes.AccessType.READ_WRITE;
import static engine.profiler.Metrics.MeasureTools.BEGIN_POST_TIME_MEASURE;
import static engine.profiler.Metrics.MeasureTools.END_POST_TIME_MEASURE_ACC;

public abstract class TPBolt extends TransactionalBolt {
    /**
     * Maps each vehicle to its average speed value that corresponds to the current 'minute number' and specified segment.
     */
    private final Map<Integer, Pair<AvgValue, SegmentIdentifier>> avgSpeedsMap = new HashMap<>();
    /**
     * The currently processed 'minute number'.
     */
    private short currentMinute = 1;

    private short time = -1;//not in use.

    public TPBolt(Logger log, int fid) {
        super(log, fid);
        this.configPrefix = "tptxn";
    }

    protected void TXN_REQUEST_NOLOCK(LREvent event, TxnContext txnContext) throws DatabaseException {
        transactionManager.SelectKeyRecord_noLock(txnContext, "segment_speed"
                , String.valueOf(event.getPOSReport().getSegment())
                , event.speed_value//holder to be filled up.
                , READ_WRITE);

        transactionManager.SelectKeyRecord_noLock(txnContext, "segment_cnt"
                , String.valueOf(event.getPOSReport().getSegment())
                , event.count_value//holder to be filled up.
                , READ_WRITE);

    }

    protected void TXN_REQUEST(LREvent event, TxnContext txnContext) throws DatabaseException, InterruptedException {
        transactionManager.SelectKeyRecord(txnContext, "segment_speed"
                , String.valueOf(event.getPOSReport().getSegment())
                , event.speed_value//holder to be filled up.
                , READ_WRITE);

        transactionManager.SelectKeyRecord(txnContext, "segment_cnt"
                , String.valueOf(event.getPOSReport().getSegment())
                , event.count_value//holder to be filled up.
                , READ_WRITE);

    }

    protected void REQUEST_LOCK_AHEAD(LREvent event, TxnContext txnContext) throws DatabaseException {

        transactionManager.lock_ahead(txnContext, "segment_speed", String.valueOf(event.getPOSReport().getSegment()), event.speed_value, READ_WRITE);
        transactionManager.lock_ahead(txnContext, "segment_cnt", String.valueOf(event.getPOSReport().getSegment()), event.count_value, READ_WRITE);

    }


    TollNotification tollNotification;

    TollNotification toll_process(Integer vid, Integer count, Double lav, short time) {
        int toll = 0;

        if (lav < 40) {

            if (count > 50) {

                //TODO: check accident. not in use in this experiment.

                { // only true if no accident was found and "break" was not executed
                    final int var = count - 50;
                    toll = 2 * var * var;
                }
            }
        }

        // TODO GetAndUpdate accurate emit time...
//        return new TollNotification(
//                time, time, vid, lav, toll);
        return null;
    }


    Tuple tuple;

    void REQUEST_POST(LREvent event) throws InterruptedException {

        tollNotification = toll_process(event.getPOSReport().getVid(), event.count, event.lav, event.getPOSReport().getTime());

        if (!enable_app_combo) {
            collector.emit(event.getBid(), true, event.getTimestamp());//the tuple is finished.
        } else {


            if (enable_latency_measurement)
                tuple = new Tuple(event.getBid(), this.thread_Id, context, new GeneralMsg<>(DEFAULT_STREAM_ID, tollNotification, event.getTimestamp()));
            else
                tuple = null;
            sink.execute(tuple);
        }
    }

    @Override
    protected void POST_PROCESS(long bid, long timestamp, int combo_bid_size) throws InterruptedException {

        for (long i = _bid; i < _bid + combo_bid_size; i++) {
            LREvent event = (LREvent) input_event;
            ((LREvent) input_event).setTimestamp(timestamp);
            BEGIN_POST_TIME_MEASURE(thread_Id);
            REQUEST_POST(event);
            END_POST_TIME_MEASURE_ACC(thread_Id);
        }
    }

    protected void TXN_REQUEST_CORE(LREvent event) {

        DataBox dataBox = event.count_value.getRecord().getValues().get(1);
        HashSet cnt_segment = dataBox.getHashSet();
        cnt_segment.add(event.getPOSReport().getVid());//update hashset; updated state also. TODO: be careful of this.
        event.count = cnt_segment.size();
        DataBox dataBox1 = event.speed_value.getRecord().getValues().get(1);
        event.lav = dataBox1.getDouble();
    }
}

