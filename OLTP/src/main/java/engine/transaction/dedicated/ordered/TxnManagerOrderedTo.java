package engine.transaction.dedicated.ordered;

import applications.util.OsUtils;
import engine.DatabaseException;
import engine.Meta.MetaTypes;
import engine.storage.SchemaRecord;
import engine.storage.SchemaRecordRef;
import engine.storage.StorageManager;
import engine.storage.TableRecord;
import engine.transaction.dedicated.TxnManagerDedicated;
import engine.transaction.impl.GlobalTimestamp;
import engine.transaction.impl.TxnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import static engine.Meta.MetaTypes.AccessType.*;
import static engine.Meta.MetaTypes.kMaxAccessNum;
import static engine.transaction.impl.TxnAccess.Access;

/**
 * Conventional Timestamp ordering from Cavalia.
 */
public class TxnManagerOrderedTo extends TxnManagerDedicated {
    private static final Logger LOG = LoggerFactory.getLogger(TxnManagerOrderedTo.class);

    public TxnManagerOrderedTo(StorageManager storageManager, String thisComponentId, int thisTaskId, int thread_count) {
        super(storageManager, thisComponentId, thisTaskId, thread_count);
        OsUtils.configLOG(LOG);
    }


    /**
     * not in use.
     * @param txn_context
     * @param table_name
     * @param record
     * @param gap
     * @return
     * @throws DatabaseException
     */
    @Override
    public boolean InsertRecord(TxnContext txn_context, String table_name, SchemaRecord record, LinkedList<Long> gap) throws DatabaseException {
        if (is_first_access_) {
            start_timestamp_ = GlobalTimestamp.GetMonotoneTimestamp();
            is_first_access_ = false;
        }
        record.is_visible_ = false;//private local write.
        TableRecord tb_record = new TableRecord(record);
        if (storageManager_.getTable(table_name).InsertRecord(tb_record)) {
            tb_record.record_.is_visible_ = true;
            Access access = access_list_.NewAccess();
            access.access_type_ = INSERT_ONLY;
            access.access_record_ = tb_record;
            access.local_record_ = null;
            access.table_id_ = table_name;
//		END_PHASE_MEASURE(thread_id_, INSERT_PHASE);
            return true;
        } else {
            //	// if the d_record has already existed, then we need to lock_ratio the original d_record.
            //	END_PHASE_MEASURE(thread_id_, INSERT_PHASE);
            return true;
        }
    }


    @Override
    protected boolean SelectRecordCC(TxnContext txn_context, String table_id, TableRecord t_record, SchemaRecordRef s_record_ref, MetaTypes.AccessType access_type) {
        if (is_first_access_) {
//			BEGIN_CC_TS_ALLOC_TIME_MEASURE(thread_id_);
            start_timestamp_ = txn_context.getBID();//@Note Assign timestamp to ts as the event's timestamp.
            is_first_access_ = false;
//			END_CC_TS_ALLOC_TIME_MEASURE(thread_id_);
        }

        final SchemaRecord local_record = new SchemaRecord(t_record.record_);//copy from t_record to local_record.

        if (access_type == READ_WRITE) {
            // write will be pushed into a queue without blocking.
            // write should be installed right before commit.
            if (!t_record.content_.RequestWriteAccess(start_timestamp_, local_record.getValues())) {
//				UPDATE_CC_ABORT_COUNT(thread_id_, context -> txn_type_, table_id);
                this.AbortTransaction();
                return false;
            }
        }
        boolean[] is_ready = new boolean[1];
        // local data may be allocated in this function.
        if (!t_record.content_.RequestReadAccess(start_timestamp_, local_record.getValues(), is_ready)) {
            // local d_record should be reclaimed here.
//			MemAllocator::Free (local_data);
//			local_record -> ~SchemaRecord();
//			MemAllocator::Free (( char*)local_record);
//			UPDATE_CC_ABORT_COUNT(thread_id_, context -> txn_type_, table_id);
            local_record.clean();
            this.AbortTransaction();
            return false;
        }
        if (!is_ready[0]) {
//			BEGIN_CC_WAIT_TIME_MEASURE(thread_id_);
//			UPDATE_CC_WAIT_COUNT(thread_id_, context -> txn_type_, table_id);
            while (!is_ready[0]) {
                //how to terminate?? is this a bug?
            }
//			END_CC_WAIT_TIME_MEASURE(thread_id_);
        }
        // here, local data must have already been allocated.
        Access access = access_list_.NewAccess();
        access.access_type_ = access_type;
        access.access_record_ = t_record;
        access.local_record_ = local_record;
        access.table_id_ = table_id;
        // reset returned d_record.
        s_record_ref.setRecord(local_record);
        return true;
    }


    @Override
    public boolean CommitTransaction(TxnContext txnContext) {
//		BEGIN_PHASE_MEASURE(thread_id_, COMMIT_PHASE);
        for (int i = 0; i < access_list_.access_count_; ++i) {
            Access access_ptr = access_list_.GetAccess(i);
            // if it is write, then install it.
            if (access_ptr.access_type_ == READ_WRITE) {
                /*volatile boolean is_ready = false;*/
                boolean[] is_read = new boolean[1];
                is_read[0] = false;
                access_ptr.access_record_.content_.RequestCommit(start_timestamp_, is_read);
//				MemAllocator::Free (access_ptr -> local_record_ -> data_ptr_);
//				access_ptr -> local_record_ -> ~SchemaRecord();
//				MemAllocator::Free (( char*)access_ptr -> local_record_);
                access_ptr.local_record_.clean();
                access_ptr.local_record_ = null;
            } else if (access_ptr.access_type_ == READ_ONLY) {
//				MemAllocator::Free (access_ptr -> local_record_ -> data_ptr_);
//				access_ptr -> local_record_ -> ~SchemaRecord();
//				MemAllocator::Free (( char*)access_ptr -> local_record_);
                if (access_ptr.local_record_ != null) {
                    access_ptr.local_record_.clean();
                    access_ptr.local_record_ = null;
                }
            }
        }
        assert (access_list_.access_count_ <= kMaxAccessNum);
        access_list_.Clear();
        // when to commit??
        is_first_access_ = true;
        // END_PHASE_MEASURE(thread_id_, COMMIT_PHASE);
        return true;
    }

    @Override
    public void AbortTransaction() {
        for (int i = 0; i < access_list_.access_count_; ++i) {
            Access access_ptr = access_list_.GetAccess(i);
            if (access_ptr.access_type_ == READ_WRITE) {
                access_ptr.access_record_.content_.RequestAbort(start_timestamp_);
            }
//			MemAllocator::Free(access_ptr->local_record_->data_ptr_);
//			access_ptr->local_record_->~SchemaRecord();
//			MemAllocator::Free((char*)access_ptr->local_record_);
            access_ptr.local_record_.clean();
        }
        assert (access_list_.access_count_ <= kMaxAccessNum);
        access_list_.Clear();
        is_first_access_ = true;
    }


}
