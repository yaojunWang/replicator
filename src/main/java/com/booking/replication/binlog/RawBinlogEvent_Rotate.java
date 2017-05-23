package com.booking.replication.binlog;

import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.google.code.or.binlog.impl.event.RotateEvent;

/**
 * Created by bosko on 5/22/17.
 */
public class RawBinlogEvent_Rotate extends RawBinlogEvent {
    public RawBinlogEvent_Rotate(Object event) throws Exception {
        super(event);
    }

    public String getBinlogFileName() {
        if (USING_DEPRECATED_PARSER) {
            return ((RotateEvent) this.getBinlogEventV4()).getBinlogFilename();
        }
        else {
            return ((RotateEventData) this.getBinlogConnectorEvent().getData()).getBinlogFilename();
        }
    }
}
