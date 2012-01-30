/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.pg;

import com.akiban.qp.loadableplan.LoadableDirectObjectPlan;
import com.akiban.qp.loadableplan.DirectObjectPlan;
import com.akiban.qp.loadableplan.DirectObjectCursor;
import com.akiban.server.service.session.Session;
import com.akiban.util.tap.InOutTap;
import com.akiban.util.tap.Tap;

import java.util.List;
import java.io.IOException;

public class PostgresLoadableDirectObjectPlan extends PostgresBaseStatement
{
    private static final InOutTap EXECUTE_TAP = Tap.createTimer("PostgresLoadableDirectObjectPlan: execute shared");
    private static final InOutTap ACQUIRE_LOCK_TAP = Tap.createTimer("PostgresLoadableDirectObjectPlan: acquire shared lock");

    private Object[] args;
    private DirectObjectPlan plan;
    private boolean useCopy;

    protected PostgresLoadableDirectObjectPlan(LoadableDirectObjectPlan loadablePlan,
                                               Object[] args)
    {
        super(loadablePlan.columnNames(),
              loadablePlan.columnTypes(),
              null);
        this.args = args;

        plan = loadablePlan.plan();
        useCopy = plan.useCopyData();
    }
    
    @Override
    protected InOutTap executeTap()
    {
        return EXECUTE_TAP;
    }

    @Override
    protected InOutTap acquireLockTap()
    {
        return ACQUIRE_LOCK_TAP;
    }

    @Override
    public TransactionMode getTransactionMode() {
        return TransactionMode.NONE;
    }
    
    @Override
    public void sendDescription(PostgresQueryContext context, boolean always)
            throws IOException {
        // The copy case will be handled below.
        if (!useCopy)
            super.sendDescription(context, always);
    }

    @Override
    public int execute(PostgresQueryContext context, int maxrows) throws IOException {
        PostgresServerSession server = context.getServer();
        PostgresMessenger messenger = server.getMessenger();
        Session session = server.getSession();
        int nrows = 0;
        DirectObjectCursor cursor = null;
        PostgresOutputter<List<?>> outputter = null;
        PostgresDirectObjectCopier copier = null;
        PostgresLoadablePlan.setParameters(context, args);
        try {
            cursor = plan.cursor(context);
            cursor.open();
            List<?> row;
            if (useCopy) {
                outputter = copier = new PostgresDirectObjectCopier(context, this);
                copier.respond();
            }
            else
                outputter = new PostgresDirectObjectOutputter(context, this);
            while ((row = cursor.next()) != null) {
                if (row.isEmpty()) {
                    messenger.flush();
                }
                else {
                    outputter.output(row);
                    nrows++;
                }
                if ((maxrows > 0) && (nrows >= maxrows))
                    break;
            }
            if (useCopy) {
                copier.done();
            }
        }
        finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        {        
            messenger.beginMessage(PostgresMessages.COMMAND_COMPLETE_TYPE.code());
            messenger.writeString("CALL " + nrows);
            messenger.sendMessage();
        }
        return nrows;
    }

}