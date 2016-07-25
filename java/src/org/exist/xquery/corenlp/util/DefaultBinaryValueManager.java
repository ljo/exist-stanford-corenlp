/*
 *   exist-stanford-corenlp: XQuery module to integrate the Stanford CoreNLP
 *   annotation pipeline library with eXist-db.
 *   Copyright (C) 2016 ljo
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.exist.xquery.corenlp.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.exist.dom.persistent.BinaryDocument;
import org.exist.util.Configuration;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.BinaryValue;
import org.exist.xquery.value.BinaryValueManager;

public class DefaultBinaryValueManager implements BinaryValueManager {
    private final static Logger LOG = LogManager.getLogger(DefaultBinaryValueManager.class);
    final List<BinaryValue> binaryValues = new ArrayList<>();
    XQueryContext context;

    public DefaultBinaryValueManager(XQueryContext context) {
	this.context = context;
    }

    @Override
    public void registerBinaryValueInstance(final BinaryValue binaryValue) {
	binaryValues.add(binaryValue);
    }

    @Override
    public void runCleanupTasks() {
	for (final BinaryValue binaryValue : binaryValues) {
	    try {
		binaryValue.close();
	    } catch (final IOException ioe) {
		LOG.error("Unable to close binary value: " + ioe.getMessage(), ioe);
	    }
	}
	binaryValues.clear();
    }
    
    @Override
    public String getCacheClass() {
	return (String) context.getBroker().getBrokerPool().getConfiguration().getProperty(Configuration.BINARY_CACHE_CLASS_PROPERTY);
    }
}
