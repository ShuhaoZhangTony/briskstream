package engine.content;

import engine.Meta.MetaTypes;
import engine.storage.SchemaRecord;
import engine.transaction.impl.TxnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class T_StreamContentImpl extends T_StreamContent {
    private static final Logger LOG = LoggerFactory.getLogger(T_StreamContentImpl.class);

    private long pwid = Long.MAX_VALUE;//previous watermark id.

    @Override
    public SchemaRecord ReadAccess(TxnContext context, MetaTypes.AccessType accessType) {
        return readValues(context.getBID());
    }

    @Override
    public SchemaRecord ReadAccess(long ts, MetaTypes.AccessType accessType) {
        return readValues(ts);
    }


    @Override
    public void WriteAccess(long ts, SchemaRecord local_record_) {
//        version.updateValues(local_record_.getValues());
//        versions.put(ts, local_record_);
//        CollectGarbage(wid);///BUGS.... Fix it.
        updateValues(ts, local_record_);//mvcc, value_list @ts=0
    }

    /**
     * Shall be called only when watermark is received.
     *
     * @param wid watermark id.
     */
//    private void CollectGarbage(long wid) {
//        if (versions.size() > kRecycleLength) {
//            ClearHistory();
//        }
//        pwid = wid;
//    }
//
//    private void ClearHistory() {
////        versions.clear();
//        versions.headMap(pwid).clear();
//    }
}
