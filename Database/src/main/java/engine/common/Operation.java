package engine.common;

import engine.Meta.MetaTypes;
import engine.storage.SchemaRecordRef;
import engine.storage.TableRecord;
import engine.storage.datatype.DataBox;
import engine.transaction.function.Condition;
import engine.transaction.function.Function;
import engine.transaction.impl.TxnContext;

import java.util.List;

//contains the place-holder to fill, as well as timestamp (bid).
public class Operation implements Comparable<Operation> {

    public final TableRecord d_record;

    public final MetaTypes.AccessType accessType;

    public final TxnContext txn_context;
    public volatile SchemaRecordRef record_ref;//required by read-only: the place holder of the reading d_record.
    public final long bid;
    //required by READ_WRITE_and Condition.
    public final Function function;


    public List<DataBox> value_list;//required by write-only: the value_list to be used to update the d_record.

    //only update corresponding column.
    public long value;
    public int column_id;

    public final String table_name;
    //required by READ_WRITE.
    public volatile TableRecord s_record;//only if it is different from d_record.
    public volatile TableRecord[] condition_records;
    public Condition condition;
    public boolean[] success;
    public String name;


    public Operation(String table_name, TxnContext txn_context, long bid, MetaTypes.AccessType accessType, TableRecord record, SchemaRecordRef record_ref, Function function) {
        this.table_name = table_name;
        this.d_record = record;
        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;


        this.function = function;
        this.s_record = d_record;

        this.record_ref = record_ref;//this holds events' record_ref.
    }

    public Operation(String table_name, TxnContext txn_context, long bid, MetaTypes.AccessType accessType, TableRecord record, SchemaRecordRef record_ref) {
        this.table_name = table_name;
        this.d_record = record;
        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;


        this.s_record = d_record;
        this.function = null;

        this.record_ref = record_ref;//this holds events' record_ref.
    }

    public Operation(String table_name, TxnContext txn_context, long bid, MetaTypes.AccessType accessType, TableRecord record, List<DataBox> value_list) {
        this.table_name = table_name;
        this.d_record = record;
        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;

        this.value_list = value_list;

        this.s_record = d_record;
        this.function = null;

        this.record_ref = null;
    }

    public Operation(String table_name, TxnContext txn_context, long bid, MetaTypes.AccessType accessType, TableRecord record, long value, int column_id) {
        this.table_name = table_name;
        this.d_record = record;
        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;

        this.value = value;

        this.column_id = column_id;

        this.s_record = d_record;
        this.function = null;

        this.record_ref = null;
    }

    /**
     * Update dest d_record by applying function of s_record.. It relys on MVCC to guarantee correctness.
     *
     * @param table_name
     * @param s_record
     * @param d_record
     * @param bid
     * @param accessType
     * @param function
     * @param txn_context
     * @param column_id
     */
    public Operation(String table_name, TableRecord s_record, TableRecord d_record, long bid, MetaTypes.AccessType accessType, Function function, TxnContext txn_context, int column_id) {
        this.table_name = table_name;
        this.d_record = d_record;
        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;

        this.s_record = s_record;
        this.function = function;

        this.record_ref = null;
        this.column_id = column_id;
    }

    /**
     * @param table_name
     * @param s_record
     * @param d_record
     * @param record_ref
     * @param bid
     * @param accessType
     * @param function
     * @param condition_records
     * @param condition
     * @param txn_context
     * @param success
     */
    public Operation(String table_name, TableRecord s_record, TableRecord d_record, SchemaRecordRef record_ref, long bid, MetaTypes.AccessType accessType, Function function, TableRecord[] condition_records, Condition condition, TxnContext txn_context, boolean[] success) {
        this.table_name = table_name;
        this.s_record = s_record;
        this.d_record = d_record;

        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;

        this.condition_records = condition_records;
        this.function = function;
        this.condition = condition;

        this.success = success;
        this.record_ref = record_ref;
    }

    public Operation(String table_name, TableRecord d_record, long bid, MetaTypes.AccessType accessType, Function function, TableRecord[] condition_records, Condition condition, TxnContext txn_context, boolean[] success) {
        this.table_name = table_name;
        this.d_record = d_record;

        this.bid = bid;
        this.accessType = accessType;
        this.txn_context = txn_context;

        this.condition_records = condition_records;
        this.function = function;
        this.condition = condition;

        this.success = success;
        this.s_record = d_record;
        this.record_ref = null;
    }


    /**
     * TODO: make it better.
     * It has an assumption that no duplicate keys for the same BID. --> This helps a lot!
     *
     * @param operation
     * @return
     */
    @Override
    public int compareTo(Operation operation) {
        if (this.bid == (operation.bid)) {
            return this.d_record.getID() - operation.d_record.getID();//different records, don't care about its bid.
        } else
            return Long.compare(this.bid, operation.bid);
    }

    public void set_worker(String name) {

        assert this.name == null;
        this.name = name;
    }
}