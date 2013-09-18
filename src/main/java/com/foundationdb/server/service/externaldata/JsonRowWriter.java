/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.service.externaldata;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.qp.operator.Cursor;
import com.foundationdb.qp.operator.RowCursor;
import com.foundationdb.qp.row.Row;
import com.foundationdb.server.types3.pvalue.PValueSource;
import com.foundationdb.util.AkibanAppender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonRowWriter
{
    private static final Logger logger = LoggerFactory.getLogger(JsonRowWriter.class);

    private final RowTracker tracker;

    public JsonRowWriter(RowTracker tracker) {
        this.tracker = tracker;
    }

    public boolean writeRows(Cursor cursor, AkibanAppender appender, String prefix, WriteRow rowWriter) {
        try {
            cursor.openTopLevel();
            return writeRowsFromOpenCursor(cursor, appender, prefix, rowWriter);
        }
        finally {
            cursor.closeTopLevel();
        }
    }

    public boolean writeRowsFromOpenCursor(RowCursor cursor, AkibanAppender appender, String prefix, WriteRow rowWriter) {
        tracker.reset();
        final int minDepth = tracker.getMinDepth();
        final int maxDepth = tracker.getMaxDepth();
        int depth = minDepth - 1;
        Row row;
        while ((row = cursor.next()) != null) {
            logger.trace("Row {}", row);
            tracker.beginRow(row);
            int rowDepth = tracker.getRowDepth();
            boolean begun = false;
            if (depth >= rowDepth) {
                if (tracker.isSameRowType())
                    begun = true;
                do {
                    appender.append((depth > rowDepth || !begun) ? "}]" : "}");
                    depth--;
                    tracker.popRowType();
                } while (depth >= rowDepth);
            }
            if (rowDepth > maxDepth)
                continue;
            assert (rowDepth == depth+1);
            depth = rowDepth;
            tracker.pushRowType();
            if (begun) {
                appender.append(',');
            }
            else if (depth > minDepth) {
                appender.append(",\"");
                appender.append(tracker.getRowName());
                appender.append("\":[");
            }
            else {
                appender.append(prefix);
            }
            appender.append('{');
            rowWriter.write(row, appender);
        }
        if (depth < minDepth)
            return false;       // Cursor was empty = not found.
        do {
            appender.append((depth > minDepth) ? "}]" : "}");
            depth--;
            tracker.popRowType();
        } while (depth >= minDepth);
        return true;
    }

    public static void writeValue(String name, PValueSource pvalue, AkibanAppender appender, boolean first) {
        if(!first) {
            appender.append(',');
        }
        appender.append('"');
        appender.append(name);
        appender.append("\":");
        pvalue.tInstance().formatAsJson(pvalue, appender);
    }

    /**
     * Write the name:value pairs of the data from a row into Json format.
     * Current implementations take names from the table columns or the
     * table's primary key columns. 
     * @author tjoneslo
     */
    public interface WriteRow {
        public void write(Row row, AkibanAppender appender);

    }
    
    public static class WriteTableRow implements WriteRow {
        @Override
        public void write(Row row, AkibanAppender appender) {
            List<Column> columns = row.rowType().userTable().getColumns();
            for (int i = 0; i < columns.size(); i++) {
                writeValue(columns.get(i).getName(), row.pvalue(i), appender, i == 0);
             }
        }
    }

    public static class WriteCapturePKRow implements WriteRow {
        private Map<Column, PValueSource> pkValues = new HashMap<>();

        @Override
        public void write(Row row, AkibanAppender appender) {
            // tables with hidden PK (noPK tables) return no values
            if (row.rowType().userTable().getPrimaryKey() == null) return;
            
            List<IndexColumn> columns = row.rowType().userTable().getPrimaryKey().getIndex().getKeyColumns();
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i).getColumn();
                writeValue(column.getName(), row.pvalue(column.getPosition()), appender, i == 0);
                pkValues.put(column, row.pvalue(column.getPosition()));
            }
        }

        public Map<Column, PValueSource> getPKValues() {
            return pkValues;
        }
    }
}