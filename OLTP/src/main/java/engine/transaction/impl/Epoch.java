package engine.transaction.impl;

public class Epoch {

    static volatile long curr_epoch_;

    public static long GetEpoch() {
        return curr_epoch_;
    }
//	boost::thread *ts_thread_;
}
