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
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.WordToSentenceProcessor;
import edu.stanford.nlp.sequences.SeqClassifierFlags;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.poi.POITextExtractor;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.xmlbeans.XmlException;

import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.security.PermissionDeniedException;
import org.exist.util.ParametersExtractor;
import org.exist.util.io.Resource;
import org.exist.util.io.TemporaryFileManager;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.corenlp.util.DefaultBinaryValueManager;
import org.exist.xquery.value.*;
import org.xml.sax.SAXException;
import org.exist.xquery.corenlp.util.Spreadsheet.InputDocType;
import org.exist.xquery.corenlp.util.Spreadsheet.OutDocType;
import org.exist.xquery.corenlp.util.Spreadsheet.TextDocType;
import org.exist.xquery.corenlp.util.Spreadsheet;

import org.jopendocument.dom.ODPackage;
import org.jopendocument.dom.ODDocument;
import org.jopendocument.dom.text.TextDocument;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;

public class Textdocument {
    private final static Logger LOG = LogManager.getLogger(Textdocument.class);

    public static String readTextDocument(final InputDocType textDocType, BinaryValueFromBinaryString uploadedFileBase64String, final String localFilePath) throws IOException {
	if (uploadedFileBase64String == null) {
	    if (localFilePath == null) {
		return readLocalTextDocument(InputDocType.ODT, "/db/temp/swe-clarin/user-selection.odt");
	    } else {
		return readLocalTextDocument(textDocType, localFilePath);
	    }
	} else {
	    return readUploadedTextDocument(textDocType, uploadedFileBase64String);
	}
    }

    private static String readUploadedTextDocument(final InputDocType textDocType, BinaryValueFromBinaryString uploadedFileBase64String) throws IOException {
	String text = "";
	

	switch (textDocType) {
	case ODT:
	    	try (InputStream is = uploadedFileBase64String.getInputStream()) {

		    TextDocument utd = ODPackage.createFromStream(is, "UserTextDocument").getTextDocument();
		    text = utd.getCharacterContent(true); //ooMode?
		}
	    break;
	case DOCX:
	    try (InputStream is = uploadedFileBase64String.getInputStream()) {
		POITextExtractor extractor = ExtractorFactory.createExtractor(is);
		text = extractor.getText();
	    } catch (InvalidFormatException ife) {
		LOG.error(ife);
	    } catch (OpenXML4JException ox4e) {
		LOG.error(ox4e);
	    } catch (XmlException xe) {
		LOG.error(xe);
	    }
	    break;
	case DOC:
	    try (InputStream is = uploadedFileBase64String.getInputStream()) {
		POITextExtractor extractor = ExtractorFactory.createExtractor(is);
		text = extractor.getText();
	    } catch (InvalidFormatException ife) {
		LOG.error(ife);
	    } catch (OpenXML4JException ox4e) {
		LOG.error(ox4e);
	    } catch (XmlException xe) {
		LOG.error(xe);
	    }
	    break;
	case TXT:
	    text = IOUtils.slurpInputStream(uploadedFileBase64String.getInputStream(), "UTF-8"); // Or null
	    break;
	}
	return text;
    }

    private static String readLocalTextDocument(final InputDocType textDocType, final String localFilePath) throws IOException {
	String text = "";

	switch (textDocType) {
	case ODT:
	    	try (InputStream is = new Resource(localFilePath).getInputStream()) {

		    TextDocument utd = ODPackage.createFromStream(is, "UserTextDocument").getTextDocument();
		    text = utd.getCharacterContent(true); //ooMode?
		}
	    break;
	case DOCX:
	    try (InputStream is = new Resource(localFilePath).getInputStream()) {
		POITextExtractor extractor = ExtractorFactory.createExtractor(is);
		//XWPFWordExtractor extractor = new XWPFWordExtractor(is);
		text = extractor.getText();
	    } catch (InvalidFormatException ife) {
		LOG.error(ife);
	    } catch (OpenXML4JException ox4e) {
		LOG.error(ox4e);
	    } catch (XmlException xe) {
		LOG.error(xe);
	    }
	    break;
	case DOC:
	    try (InputStream is = new Resource(localFilePath).getInputStream()) {
		POITextExtractor extractor = ExtractorFactory.createExtractor(is);
		//XWPFWordExtractor extractor = new XWPFWordExtractor(is);
		text = extractor.getText();
	    } catch (InvalidFormatException ife) {
		LOG.error(ife);
	    } catch (OpenXML4JException ox4e) {
		LOG.error(ox4e);
	    } catch (XmlException xe) {
		LOG.error(xe);
	    }
	    break;
	case TXT:
	    File file = new Resource(localFilePath);
	    text = IOUtils.slurpFileNoExceptions(file);
	    break;
	}
	return text;
    }
}
