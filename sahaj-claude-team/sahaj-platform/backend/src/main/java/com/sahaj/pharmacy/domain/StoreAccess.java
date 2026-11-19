package com.sahaj.pharmacy.domain;

/** The derived access columns of one person in one store (docs/api/pharmacy.yaml StoreAccess). */
public record StoreAccess(boolean raiseIndent, boolean approve, boolean receive, boolean receiveScheduleX,
                          boolean consumeReturn, boolean uploadOpeningStock, boolean readStock, boolean postOpeningStock,
                          boolean adjustStock) {

    /** A person who cannot sign in, or whose mapping was removed, can do nothing. */
    public static final StoreAccess NONE = new StoreAccess(false, false, false, false, false, false, false, false, false);

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
            case ADJUST_STOCK:
                return adjustStock;
            default:
                return false;
        }
    }
}
