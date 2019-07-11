package applications.general.topology.transactional;


import applications.general.bolts.ob.OBBolt_lwm;
import applications.general.bolts.ob.OBBolt_olb;
import applications.general.bolts.ob.OBBolt_sstore;
import applications.general.bolts.ob.OBBolt_ts;
import applications.constants.OnlineBidingSystemConstants.Component;
import applications.general.topology.transactional.initializer.OBInitializer;
import applications.general.topology.transactional.initializer.TableInitilizer;
import applications.util.Configuration;
import brisk.components.Topology;
import brisk.components.exception.InvalidIDException;
import brisk.components.grouping.ShuffleGrouping;
import brisk.controller.input.scheduler.SequentialScheduler;
import brisk.topology.TransactionTopology;
import engine.common.PartitionedOrderLock;
import engine.common.SpinLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static applications.CONTROL.enable_app_combo;
import static applications.constants.OnlineBidingSystemConstants.Conf.OB_THREADS;
import static applications.constants.OnlineBidingSystemConstants.PREFIX;
import static engine.profiler.Metrics.NUM_ITEMS;
import static utils.PartitionHelper.setPartition_interval;

/**
 * Short term as OB.
 */
public class OnlineBiding extends TransactionTopology {
    private static final Logger LOG = LoggerFactory.getLogger(OnlineBiding.class);

    public OnlineBiding(String topologyName, Configuration config) {
        super(topologyName, config);
    }

    public static String getPrefix() {
        return PREFIX;
    }

    static int GenerateInteger(final int min, final int max) {
        Random r = new Random();
        return r.nextInt(max) + min;
    }
    //configure set_executor_ready database table.
    public TableInitilizer initializeDB(SpinLock[] spinlock_){
        double scale_factor = config.getDouble("scale_factor", 1);
        double theta = config.getDouble("theta", 1);
        int tthread = config.getInt("tthread");
        setPartition_interval((int) (Math.ceil(NUM_ITEMS / (double) tthread)), tthread);

        TableInitilizer ini = new OBInitializer(db, scale_factor, theta, tthread, config);

        ini.creates_Table(config);

        if (config.getBoolean("partition", false)) {

            for (int i = 0; i < tthread; i++)
                spinlock_[i] = new SpinLock();

//            ini.loadDB(scale_factor, theta, getPartition_interval(), spinlock_);

            //initialize order locks.
            PartitionedOrderLock.getInstance().initilize(tthread);
        } else {
//            ini.loadDB(scale_factor, theta);
        }
        double ratio_of_read = config.getDouble("ratio_of_read", 0.5);

        return ini;
    }
    @Override
    public Topology buildTopology() {


        try {
            //Spout needs to put two types of events: deposits and transfers
            //Deposits put values into Accounts and the Book
            //Transfers atomically move values between accounts and book entries, under a precondition
            builder.setSpout(Component.SPOUT, spout, spoutThreads);

            if (enable_app_combo) {
                //spout only.

            } else {
                switch (config.getInt("CCOption", 0)) {


                    case 1: {//LOB
                        builder.setBolt(Component.OB, new OBBolt_olb(0)//
                                , config.getInt(OB_THREADS, 1)
                                , new ShuffleGrouping(Component.SPOUT));
                        break;
                    }

                    case 2: {//LWM
                        builder.setBolt(Component.OB, new OBBolt_lwm(0)//
                                , config.getInt(OB_THREADS, 1)
                                , new ShuffleGrouping(Component.SPOUT));
                        break;
                    }
//
                    case 3: {//T-Stream
                        builder.setBolt(Component.OB, new OBBolt_ts(0)//
                                , config.getInt(OB_THREADS, 1)
                                , new ShuffleGrouping(Component.SPOUT));
                        break;
                    }
                    case 4: {//SStore
                        builder.setBolt(Component.OB, new OBBolt_sstore(0)//
                                , config.getInt(OB_THREADS, 1)
                                , new ShuffleGrouping(Component.SPOUT));
                        break;
                    }
                }

                builder.setSink(Component.SINK, sink, sinkThreads
                        , new ShuffleGrouping(Component.OB)
                );
            }
        } catch (InvalidIDException e) {
            e.printStackTrace();
        }
        builder.setGlobalScheduler(new SequentialScheduler());
        return builder.createTopology(db, this);
    }

    @Override
    public Logger getLogger() {
        return LOG;
    }

    @Override
    public String getConfigPrefix() {
        return PREFIX;
    }
}
