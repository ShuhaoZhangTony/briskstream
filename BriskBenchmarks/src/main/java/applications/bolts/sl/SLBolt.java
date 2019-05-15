package applications.bolts.sl;

import applications.param.sl.DepositEvent;
import applications.param.sl.TransactionEvent;
import brisk.components.operators.api.TransactionalBolt;
import brisk.execution.runtime.tuple.impl.Tuple;
import brisk.execution.runtime.tuple.impl.msgs.GeneralMsg;
import engine.DatabaseException;
import engine.storage.datatype.DataBox;
import engine.transaction.impl.TxnContext;
import org.slf4j.Logger;

import java.util.List;

import static applications.CONTROL.enable_app_combo;
import static applications.CONTROL.enable_latency_measurement;
import static applications.Constants.DEFAULT_STREAM_ID;
import static engine.Meta.MetaTypes.AccessType.READ_WRITE;
import static engine.profiler.Metrics.MeasureTools.BEGIN_POST_TIME_MEASURE;
import static engine.profiler.Metrics.MeasureTools.END_POST_TIME_MEASURE;

public abstract class SLBolt extends TransactionalBolt {

    public SLBolt(Logger log, int fid) {
        super(log, fid);
        this.configPrefix = "sl";
    }

    protected void DEPOSITE_REQUEST_NOLOCK(DepositEvent event, TxnContext txnContext) throws DatabaseException {
        transactionManager.SelectKeyRecord_noLock(txnContext, "accounts", event.getAccountId(), event.account_value, READ_WRITE);
        transactionManager.SelectKeyRecord_noLock(txnContext, "bookEntries", event.getBookEntryId(), event.asset_value, READ_WRITE);

        assert event.account_value.getRecord() != null && event.asset_value.getRecord() != null;
    }

    protected void TRANSFER_REQUEST_NOLOCK(TransactionEvent event, TxnContext txnContext) throws DatabaseException {
        transactionManager.SelectKeyRecord_noLock(txnContext, "accounts", event.getSourceAccountId(), event.src_account_value, READ_WRITE);
        transactionManager.SelectKeyRecord_noLock(txnContext, "accounts", event.getTargetAccountId(), event.dst_account_value, READ_WRITE);
        transactionManager.SelectKeyRecord_noLock(txnContext, "bookEntries", event.getSourceBookEntryId(), event.src_asset_value, READ_WRITE);
        transactionManager.SelectKeyRecord_noLock(txnContext, "bookEntries", event.getTargetBookEntryId(), event.dst_asset_value, READ_WRITE);
        assert event.src_account_value.getRecord() != null && event.dst_account_value.getRecord() != null && event.src_asset_value.getRecord() != null && event.dst_asset_value.getRecord() != null;
    }

    protected void DEPOSITE_REQUEST(DepositEvent event, TxnContext txnContext) throws DatabaseException, InterruptedException {
        transactionManager.SelectKeyRecord(txnContext, "accounts", event.getAccountId(), event.account_value, READ_WRITE);
        transactionManager.SelectKeyRecord(txnContext, "bookEntries", event.getBookEntryId(), event.asset_value, READ_WRITE);

        assert event.account_value.getRecord() != null && event.asset_value.getRecord() != null;
    }

    protected void TRANSFER_REQUEST(TransactionEvent event, TxnContext txnContext) throws DatabaseException, InterruptedException {
        transactionManager.SelectKeyRecord(txnContext, "accounts", event.getSourceAccountId(), event.src_account_value, READ_WRITE);
        transactionManager.SelectKeyRecord(txnContext, "accounts", event.getTargetAccountId(), event.dst_account_value, READ_WRITE);
        transactionManager.SelectKeyRecord(txnContext, "bookEntries", event.getSourceBookEntryId(), event.src_asset_value, READ_WRITE);
        transactionManager.SelectKeyRecord(txnContext, "bookEntries", event.getTargetBookEntryId(), event.dst_asset_value, READ_WRITE);
        assert event.src_account_value.getRecord() != null && event.dst_account_value.getRecord() != null && event.src_asset_value.getRecord() != null && event.dst_asset_value.getRecord() != null;
    }

    protected void TRANSFER_LOCK_AHEAD(TransactionEvent event, TxnContext txnContext) throws DatabaseException {
        transactionManager.lock_ahead(txnContext, "accounts", event.getSourceAccountId(), event.src_account_value, READ_WRITE);
        transactionManager.lock_ahead(txnContext, "accounts", event.getTargetAccountId(), event.dst_account_value, READ_WRITE);
        transactionManager.lock_ahead(txnContext, "bookEntries", event.getSourceBookEntryId(), event.src_asset_value, READ_WRITE);
        transactionManager.lock_ahead(txnContext, "bookEntries", event.getTargetBookEntryId(), event.dst_asset_value, READ_WRITE);
    }

    protected void DEPOSITE_LOCK_AHEAD(DepositEvent event, TxnContext txnContext) throws DatabaseException {
        transactionManager.lock_ahead(txnContext, "accounts", event.getAccountId(), event.account_value, READ_WRITE);
        transactionManager.lock_ahead(txnContext, "bookEntries", event.getBookEntryId(), event.asset_value, READ_WRITE);

    }


    protected void TRANSFER_REQUEST_CORE(TransactionEvent event) throws InterruptedException {
        // measure_end the preconditions

        DataBox sourceAccountBalance_value = event.src_account_value.getRecord().getValues().get(1);
        final long sourceAccountBalance = sourceAccountBalance_value.getLong();

        DataBox sourceAssetValue_value = event.src_asset_value.getRecord().getValues().get(1);
        final long sourceAssetValue = sourceAssetValue_value.getLong();

        DataBox targetAccountBalance_value = event.dst_account_value.getRecord().getValues().get(1);
        final long targetAccountBalance = targetAccountBalance_value.getLong();

        DataBox targetAssetValue_value = event.dst_asset_value.getRecord().getValues().get(1);
        final long targetAssetValue = targetAssetValue_value.getLong();


        if (sourceAccountBalance > event.getMinAccountBalance()
                && sourceAccountBalance > event.getAccountTransfer()
                && sourceAssetValue > event.getBookEntryTransfer()) {

            // compute the new balances
            final long newSourceBalance = sourceAccountBalance - event.getAccountTransfer();
            final long newTargetBalance = targetAccountBalance + event.getAccountTransfer();
            final long newSourceAssets = sourceAssetValue - event.getBookEntryTransfer();
            final long newTargetAssets = targetAssetValue + event.getBookEntryTransfer();


            // write back the updated values
            sourceAccountBalance_value.setLong(newSourceBalance);
            targetAccountBalance_value.setLong(newTargetBalance);

            targetAccountBalance_value.setLong(newSourceAssets);
            targetAssetValue_value.setLong(newTargetAssets);


            event.transaction_result = new TransactionResult(event, true, newSourceBalance, newTargetBalance);

        } else {
            event.transaction_result = new TransactionResult(event, false, sourceAccountBalance, targetAccountBalance);
        }
    }


    protected void DEPOSITE_REQUEST_CORE(DepositEvent event) {
        List<DataBox> values = event.account_value.getRecord().getValues();

        long newAccountValue = values.get(1).getLong() + event.getAccountTransfer();

        values.get(1).setLong(newAccountValue);

        List<DataBox> asset_values = event.asset_value.getRecord().getValues();

        long newAssetValue = values.get(1).getLong() + event.getBookEntryTransfer();

        asset_values.get(1).setLong(newAssetValue);

//        collector.force_emit(input_event.getBid(), null, input_event.getTimestamp());
    }

    //post stream processing phase..
    protected void POST_PROCESS(long _bid, long timestamp, int combo_bid_size) throws InterruptedException {
        BEGIN_POST_TIME_MEASURE(thread_Id);
        for (long i = _bid; i < _bid + combo_bid_size; i++) {

            if (input_event instanceof DepositEvent) {
                ((DepositEvent) input_event).setTimestamp(timestamp);
                DEPOSITE_REQUEST_POST((DepositEvent) input_event);
            } else {
                ((TransactionEvent) input_event).setTimestamp(timestamp);
                TRANSFER_REQUEST_POST((TransactionEvent) input_event);
            }
        }
        END_POST_TIME_MEASURE(thread_Id);
    }

    protected void TRANSFER_REQUEST_POST(TransactionEvent event) throws InterruptedException {
        if (!enable_app_combo) {
            collector.emit(event.getBid(), true, event.getTimestamp());//the tuple is finished.
        } else {
            if (enable_latency_measurement) {
                sink.execute(new Tuple(event.getBid(), this.thread_Id, context, new GeneralMsg<>(DEFAULT_STREAM_ID, event.transaction_result, event.getTimestamp())));
            }
        }


    }

    void DEPOSITE_REQUEST_POST(DepositEvent event) throws InterruptedException {
        if (!enable_app_combo) {
            collector.emit(event.getBid(), true, event.getTimestamp());//the tuple is finished.
        } else {
            if (enable_latency_measurement) {
                sink.execute(new Tuple(event.getBid(), this.thread_Id, context, new GeneralMsg<>(DEFAULT_STREAM_ID, true, event.getTimestamp())));
            }
        }
    }


    protected void LAL_PROCESS(long _bid) throws InterruptedException, DatabaseException {
    }
}
