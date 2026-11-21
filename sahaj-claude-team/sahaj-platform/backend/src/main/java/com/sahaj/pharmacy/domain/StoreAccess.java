package com.sahaj.pharmacy.domain;

/**
 * The derived access columns of one person in one store (docs/api/pharmacy.yaml StoreAccess, 0.3.6). The last twelve are the
 * SAH-80 sale capabilities (ADR-030 S2); they are false unless {@link StoreRules#access(String, StoreRole, boolean, boolean, boolean)}
 * works them out from a store that allows sale.
 */
public record StoreAccess(boolean raiseIndent, boolean approve, boolean receive, boolean receiveScheduleX,
                          boolean consumeReturn, boolean uploadOpeningStock, boolean readStock, boolean postOpeningStock,
                          boolean adjustStock, boolean returnDamage, boolean dispatchTransfer, boolean consume,
                          boolean startCount, boolean count, boolean reviewCount, boolean handleScheduleX,
                          boolean sell, boolean sellScheduled, boolean substitute, boolean overrideBlock,
                          boolean cancelBill, boolean salesReturn, boolean acceptSalesReturn, boolean dispense,
                          boolean recordWardReturn, boolean acceptWardReturn, boolean readRegisters, boolean dayClose) {

    /** A person who cannot sign in, or whose mapping was removed, can do nothing. */
    public static final StoreAccess NONE = new StoreAccess(false, false, false, false, false, false, false, false, false);

    /** The sixteen SAH-79 columns; every sale capability is false. */
    public StoreAccess(boolean raiseIndent, boolean approve, boolean receive, boolean receiveScheduleX, boolean consumeReturn,
                       boolean uploadOpeningStock, boolean readStock, boolean postOpeningStock, boolean adjustStock,
                       boolean returnDamage, boolean dispatchTransfer, boolean consume, boolean startCount, boolean count,
                       boolean reviewCount, boolean handleScheduleX) {
        this(raiseIndent, approve, receive, receiveScheduleX, consumeReturn, uploadOpeningStock, readStock, postOpeningStock,
                adjustStock, returnDamage, dispatchTransfer, consume, startCount, count, reviewCount, handleScheduleX,
                false, false, false, false, false, false, false, false, false, false, false, false);
    }

    /**
     * The nine original columns. The seven 0.3.6 flags that follow from them take their values (returnDamage, dispatchTransfer
     * and count = readStock, consume = consumeReturn, handleScheduleX = receiveScheduleX); startCount and reviewCount need
     * the store role, so they are false here and {@link StoreRules#access} sets them.
     */
    public StoreAccess(boolean raiseIndent, boolean approve, boolean receive, boolean receiveScheduleX, boolean consumeReturn,
                       boolean uploadOpeningStock, boolean readStock, boolean postOpeningStock, boolean adjustStock) {
        this(raiseIndent, approve, receive, receiveScheduleX, consumeReturn, uploadOpeningStock, readStock, postOpeningStock,
                adjustStock, readStock, readStock, consumeReturn, false, readStock, false, receiveScheduleX);
    }

    /** The same access with the twelve sale capabilities set. */
    public StoreAccess withSale(boolean sell, boolean sellScheduled, boolean cancelBill, boolean salesReturn,
                                boolean acceptSalesReturn, boolean dispense, boolean recordWardReturn, boolean readRegisters,
                                boolean dayClose) {
        return new StoreAccess(raiseIndent, approve, receive, receiveScheduleX, consumeReturn, uploadOpeningStock, readStock,
                postOpeningStock, adjustStock, returnDamage, dispatchTransfer, consume, startCount, count, reviewCount,
                handleScheduleX, sell, sellScheduled, sellScheduled, sellScheduled, cancelBill, salesReturn,
                acceptSalesReturn, dispense, recordWardReturn, dispense, readRegisters, dayClose);
    }

    public boolean has(StoreCapability capability) {
        switch (capability) {
            case RAISE_INDENT:
                return raiseIndent;
            case APPROVE:
                return approve;
            case RECEIVE:
                return receive;
            case RECEIVE_SCHEDULE_X:
                return receiveScheduleX;
            case CONSUME_RETURN:
                return consumeReturn;
            case UPLOAD_OPENING_STOCK:
                return uploadOpeningStock;
            case READ_STOCK:
                return readStock;
            case POST_OPENING_STOCK:
                return postOpeningStock;
            case RETURN_DAMAGE:
                return returnDamage;
            case ADJUST_STOCK:
                return adjustStock;
            case DISPATCH_TRANSFER:
                return dispatchTransfer;
            case CONSUME:
                return consume;
            case START_COUNT:
                return startCount;
            case COUNT:
                return count;
            case REVIEW_COUNT:
                return reviewCount;
            case HANDLE_SCHEDULE_X:
                return handleScheduleX;
            case SELL:
                return sell;
            case SELL_SCHEDULED:
                return sellScheduled;
            case SUBSTITUTE:
                return substitute;
            case OVERRIDE_BLOCK:
                return overrideBlock;
            case CANCEL_BILL:
                return cancelBill;
            case SALES_RETURN:
                return salesReturn;
            case ACCEPT_SALES_RETURN:
                return acceptSalesReturn;
            case DISPENSE:
                return dispense;
            case RECORD_WARD_RETURN:
                return recordWardReturn;
            case ACCEPT_WARD_RETURN:
                return acceptWardReturn;
            case READ_REGISTERS:
                return readRegisters;
            case DAY_CLOSE:
                return dayClose;
            default:
                return false;
        }
    }
}
