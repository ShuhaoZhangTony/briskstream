package engine.transaction.dedicated;

import applications.util.OsUtils;
import engine.DatabaseException;
import engine.Meta.MetaTypes;
import engine.content.Content;
import engine.profiler.Metrics;
import engine.storage.SchemaRecord;
import engine.storage.SchemaRecordRef;
import engine.storage.StorageManager;
import engine.storage.TableRecord;
import engine.transaction.impl.TxnContext;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import static applications.CONTROL.enable_debug;
import static engine.Meta.MetaTypes.AccessType.*;
import static engine.Meta.MetaTypes.kMaxAccessNum;
import static engine.profiler.MeasureTools.BEGIN_TP_CORE_TIME_MEASURE;
import static engine.profiler.MeasureTools.END_TP_CORE_TIME_MEASURE_NOCC;
import static engine.transaction.impl.TxnAccess.Access;

/**
 * conventional two-phase locking with no-sync_ratio strategy from Cavalia.
 */
public class TxnManagerLock extends TxnManagerDedicated {
    private static final Logger LOG = LoggerFactory.getLogger(TxnManagerLock.class);
    private Metrics metrics = Metrics.getInstance();

    public TxnManagerLock(StorageManager storageManager, String thisComponentId, int thisTaskId, int thread_count) {
        super(storageManager, thisComponentId, thisTaskId, thread_count);
        OsUtils.configLOG(LOG);
    }

    @Override
    public boolean InsertRecord(TxnContext txn_context, String table_name, SchemaRecord record, LinkedList<Long> gap) throws DatabaseException {
//		BEGIN_PHASE_MEASURE(thread_id_, INSERT_PHASE);
        record.is_visible_ = false;
        TableRecord tb_record = new TableRecord(record);
        if (storageManager_.getTable(table_name).InsertRecord(tb_record)) {//maybe we can also skip this for testing purpose.
            if (!tb_record.content_.TryWriteLock()) {
                this.AbortTransaction();
                return false;
            } else {
//				LOG.info(tb_record.toString() + "is locked by insertor");
            }
            record.is_visible_ = true;
            Access access = access_list_.NewAccess();
            access.access_type_ = INSERT_ONLY;
            access.access_record_ = tb_record;
            access.local_record_ = null;
            access.table_id_ = table_name;
            access.timestamp_ = 0;
//		END_PHASE_MEASURE(thread_id_, INSERT_PHASE);
            return true;
        } else {
//				END_PHASE_MEASURE(thread_id_, INSERT_PHASE);
            return true;
        }
    }

    @Override
    public boolean CommitTransaction(TxnContext txnContext) {
//        BEGIN_TS_ALLOCATE_TIME_MEASURE(txnContext.thread_Id);
////		BEGIN_PHASE_MEASURE(thread_id_, COMMIT_PHASE);
////#if defined(SCALABLE_TIMESTAMP)
//        long max_rw_ts = 0;
//        for (int i = 0; i < access_list_.access_count_; ++i) {
//            Access access_ptr = access_list_.GetAccess(i);
//            if (access_ptr.timestamp_ > max_rw_ts) {
//                max_rw_ts = access_ptr.timestamp_;
//            }
//        }
////#endif
////		BEGIN_CC_TS_ALLOC_TIME_MEASURE(thread_id_);
////        long start = System.nanoTime();
//        long curr_epoch = Epoch.GetEpoch();
////#if defined(SCALABLE_TIMESTAMP)
//        long commit_ts = GenerateScalableTimestamp(curr_epoch, max_rw_ts);
////#else
////        long commit_ts = GenerateMonotoneTimestamp(curr_epoch, GlobalTimestamp.GetMonotoneTimestamp());
//
//        END_TS_ALLOCATE_TIME_MEASURE(txnContext.thread_Id);
////		END_CC_TS_ALLOC_TIME_MEASURE(thread_id_);
//
//        for (int i = 0; i < access_list_.access_count_; ++i) {
//            Access access_ptr = access_list_.GetAccess(i);
//            Content content_ref = access_ptr.access_record_.content_;
//            if (access_ptr.access_type_ == READ_WRITE) {
//                assert (commit_ts >= access_ptr.timestamp_);
//                content_ref.SetTimestamp(commit_ts);
//            } else if (access_ptr.access_type_ == INSERT_ONLY) {
//                assert (commit_ts >= access_ptr.timestamp_);
//                content_ref.SetTimestamp(commit_ts);
//            } else if (access_ptr.access_type_ == DELETE_ONLY) {
//                assert (commit_ts >= access_ptr.timestamp_);
//                content_ref.SetTimestamp(commit_ts);
//            }
//        }

        // commit.
//#if defined(VALUE_LOGGING)
//		logger_->CommitTransaction(this->thread_id_, curr_epoch, commit_ts, access_list_);
//#elif defined(COMMAND_LOGGING)
//		if (context->is_adhoc_ == true){
//			logger_->CommitTransaction(this->thread_id_, curr_epoch, commit_ts, access_list_);
//		}
//		logger_->CommitTransaction(this->thread_id_, curr_epoch, commit_ts, context->txn_type_, param);
//#endif

        // release locks.
        for (int i = 0; i < access_list_.access_count_; ++i) {
            Access access_ptr = access_list_.GetAccess(i);
            if (access_ptr.access_type_ == READ_ONLY) {
                access_ptr.access_record_.content_.ReleaseReadLock();
            } else if (access_ptr.access_type_ == READ_WRITE) {
                SchemaRecord local_record_ptr = access_ptr.local_record_;
                access_ptr.access_record_.content_.ReleaseWriteLock();
                local_record_ptr.clean();
            } else {
                // insert_only or delete_only
                access_ptr.access_record_.content_.ReleaseWriteLock();
            }
        }
        assert (access_list_.access_count_ <= kMaxAccessNum);
        access_list_.Clear();
//		END_PHASE_MEASURE(thread_id_, COMMIT_PHASE);
        return true;
    }

    @Override
    public void AbortTransaction() {
        // recover updated data and release locks.
        for (int i = 0; i < access_list_.access_count_; ++i) {
            Access access_ptr = access_list_.GetAccess(i);
            SchemaRecord local_record_ptr = access_ptr.local_record_;
            Content content_ref = access_ptr.access_record_.content_;
            if (access_ptr.access_type_ == READ_ONLY) {
                content_ref.ReleaseReadLock();
            } else if (access_ptr.access_type_ == READ_WRITE) {
//				access_ptr.access_record_.record_ = new SchemaRecord(local_record_ptr);
                content_ref.ReleaseWriteLock();
//				MemAllocator::Free(local_record_ptr->data_ptr_);
//				local_record_ptr->~SchemaRecord();
//				MemAllocator::Free((char*)local_record_ptr);
                local_record_ptr.clean();
                access_ptr.local_record_ = null;
            } else if (access_ptr.access_type_ == INSERT_ONLY) {
                access_ptr.access_record_.record_.is_visible_ = false;
                content_ref.ReleaseWriteLock();
            } else if (access_ptr.access_type_ == DELETE_ONLY) {
                access_ptr.access_record_.record_.is_visible_ = true;
                content_ref.ReleaseWriteLock();
            }
        }
        assert (access_list_.access_count_ <= kMaxAccessNum);
        access_list_.Clear();
    }


    @Override
    protected boolean SelectRecordCC(TxnContext txn_context, String table_name, TableRecord
            t_record, SchemaRecordRef record_ref, MetaTypes.AccessType accessType) {

        record_ref.setRecord(t_record.record_); //return the table record for modifying in the application layer.
        if (accessType == READ_ONLY) {
            // if cannot get lock_ratio, then return immediately.
            if (!t_record.content_.TryReadLock()) {
                this.AbortTransaction();
                return false;
            } else {
//                if (!txn_context.is_retry_)
                    BEGIN_TP_CORE_TIME_MEASURE(txn_context.thread_Id);
                Access access = access_list_.NewAccess();
                access.access_type_ = READ_ONLY;
                access.access_record_ = t_record;
                access.local_record_ = null;
                access.table_id_ = table_name;
                access.timestamp_ = t_record.content_.GetTimestamp();
//                if (!txn_context.is_retry_)
                    END_TP_CORE_TIME_MEASURE_NOCC(txn_context.thread_Id);
                return true;
            }
        } else if (accessType == READ_WRITE) {
            if (!t_record.content_.TryWriteLock()) {
                if (enable_debug)
                    LOG.info(Thread.currentThread().getName() + " failed to get lock_ratio" + DateTime.now());
                this.AbortTransaction();
                return false;
            } else {
//                LOG.trace(txn_context.getThisOpId() + " success to get lock_ratio" + DateTime.now());

//                if (!txn_context.is_retry_)
                    BEGIN_TP_CORE_TIME_MEASURE(txn_context.thread_Id);
                /**
                 * 	 const RecordSchema *schema_ptr = t_record->record_->schema_ptr_;
                 char *local_data = MemAllocator::Alloc(schema_ptr->GetSchemaSize());
                 SchemaRecord *local_record = (SchemaRecord*)MemAllocator::Alloc(sizeof(SchemaRecord));
                 new(local_record)SchemaRecord(schema_ptr, local_data);
                 t_record->record_->CopyTo(local_record);
                 */
                final SchemaRecord local_record = new SchemaRecord(t_record.record_);//copy from t_record to local_record. This is kept for potential abort.

                /**
                 Access *access = access_list_.NewAccess();
                 access->access_type_ = READ_WRITE;
                 access->access_record_ = t_record;
                 access->local_record_ = local_record;
                 access->table_id_ = table_id;
                 access->timestamp_ = t_record->content_.GetTimestamp();
                 return true;
                 */
                Access access = access_list_.NewAccess();
                access.access_type_ = READ_WRITE;
                access.access_record_ = t_record;
                access.local_record_ = local_record;
                access.table_id_ = table_name;
                access.timestamp_ = t_record.content_.GetTimestamp();

//                if (!txn_context.is_retry_)
                    END_TP_CORE_TIME_MEASURE_NOCC(txn_context.thread_Id);
                return true;
            }
        } else if (accessType == DELETE_ONLY) {
            if (!t_record.content_.TryWriteLock()) {
                this.AbortTransaction();
                return false;
            } else {
                LOG.info(t_record.toString() + "is locked by deletor");
                t_record.record_.is_visible_ = false;
                Access access = access_list_.NewAccess();
                access.access_type_ = DELETE_ONLY;
                access.access_record_ = t_record;
                access.local_record_ = null;
                access.table_id_ = table_name;
                access.timestamp_ = t_record.content_.GetTimestamp();

                return true;
            }
        } else {
            assert (false);
            return false;
        }
    }


}
