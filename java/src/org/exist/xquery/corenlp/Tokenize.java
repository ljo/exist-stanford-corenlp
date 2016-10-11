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
package org.exist.xquery.corenlp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.exist.xquery.corenlp.util.Textdocument;

import org.jopendocument.dom.ODPackage;
import org.jopendocument.dom.ODDocument;
import org.jopendocument.dom.text.TextDocument;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;

public class Tokenize extends BasicFunction {
    private final static Logger LOG = LogManager.getLogger(Tokenize.class);

    public final static FunctionSignature signatures[] = {
            new FunctionSignature(
                new QName("tokenize-string", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Tokenize the provided text string. Returns a sequence of text nodes.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("tokenizer", Type.STRING, Cardinality.EXACTLY_ONE,
                        "The fully qualified name of an alternative tokenizer to load. Must be avaliable on the classpath."),
                        new FunctionParameterSequenceType("text", Type.STRING, Cardinality.EXACTLY_ONE,
                                "String of text to analyze.")
                },
                new FunctionReturnSequenceType(Type.STRING, Cardinality.ONE_OR_MORE,
                    "Sequence of segmented tokens from the string")
            ),
            new FunctionSignature(
                new QName("tokenize-wp-doc", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Tokenize the text in the provided wordprocessing document. Returns a spreadsheet with one token per row annotated with the background symbol in column two.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("tokenizer", Type.STRING, Cardinality.EXACTLY_ONE,
                        "The fully qualified name of an alternative tokenizer to load. Must be available on the classpath."),
		    new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                "The input and output document configuration, e.g. &lt;parameters&gt;&lt;param name='inputFormat' value='odt'/&gt;&lt;param name='outputFormat' value='ods'/&gt;&lt;/parameters&gt;. Available odt->ods (default), docx->xlsx, doc->xls or txt->tsv."),
                        new FunctionParameterSequenceType("uploaded-file", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                                "The uploaded file to tokenize and format. If no file is posted in the request you need to provide localFilePath in the configuration parameter.")
                },
                new FunctionReturnSequenceType(Type.ITEM, Cardinality.EXACTLY_ONE,
                        "A spreadsheet with two columns. The token and the background symbol")
            ),
            new FunctionSignature(
                new QName("tokenize-node", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Tokenize a node and all its sub-nodes. Returns a spreadsheet with two columns.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("tokenizer", Type.STRING, Cardinality.EXACTLY_ONE,
                        "The fully qualified name of an alternative tokenizer to load. Must be available on the classpath."),
                    new FunctionParameterSequenceType("node", Type.NODE, Cardinality.EXACTLY_ONE,
                        "The node to process.")
                },
                new FunctionReturnSequenceType(Type.ITEM, Cardinality.EXACTLY_ONE,
                        "A spreadsheet with the text node tokens in column one and annotation background symbol in column two")
            )
    };

    private Path tempInFile = null;
    private Path tempOutFile = null;
    private BinaryValueFromBinaryString uploadedFileBase64String = null;
    private static PTBTokenizer<CoreLabel> cachedTokenizer = null;
    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();

    private InputDocType inputFormat = InputDocType.ODT;
    private OutDocType outputFormat = OutDocType.ODS;
    private String backgroundSymbol = "O";
    private String localFilePath = null;
    private boolean tokenizeNLs = false;

    public Tokenize(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        String tokenizerClassPath = args[0].getStringValue();

        context.pushDocumentContext();
        try {
	    String text = "";
            if (isCalledAs("tokenize-wp-doc")) {
		if (!args[1].isEmpty()) {
		    parameters = ParametersExtractor.parseParameters(((NodeValue)args[1].itemAt(0)).getNode());
		}
		if (!args[2].isEmpty()) {
		    uploadedFileBase64String = new BinaryValueFromBinaryString(new Base64BinaryValueType(), args[2].getStringValue());
		}

		tempInFile = TemporaryFileManager.getInstance().getTemporaryFile();
		tempOutFile = TemporaryFileManager.getInstance().getTemporaryFile();

            } else if (isCalledAs("tokenize-string")) {
                text = args[1].getStringValue();
            }

	    for (String property : parameters.stringPropertyNames()) {
		if ("inputFormat".equals(property)) {
		    String value = parameters.getProperty(property);
		    if ("odt".equals(value)) {
			inputFormat = InputDocType.ODT;
		    } else if ("docx".equals(value)) {
			inputFormat = InputDocType.DOCX;
		    } else if ("doc".equals(value)) {
			inputFormat = InputDocType.DOC;
		    } else if ("txt".equals(value)) {
			inputFormat = InputDocType.TXT;
		    }
		} else if ("outputFormat".equals(property)) {
		    String value = parameters.getProperty(property);
		    if ("ods".equals(value)) {
			outputFormat = OutDocType.ODS;
		    } else if ("xslx".equals(value)) {
			outputFormat = OutDocType.XSLX;
		    } else if ("xsl".equals(value)) {
			outputFormat = OutDocType.XSL;
		    } else if ("tsv".equals(value)) {
			outputFormat = OutDocType.TSV;
		    }
		} else if ("backgroundSymbol".equals(property)) {
		    String value = parameters.getProperty(property);
		    backgroundSymbol = value;
		} else if ("localFilePath".equals(property)) {
		    String value = parameters.getProperty(property);
		    localFilePath = value;
		} else if ("tokenizeNLs".equals(property)) {
		    String value = parameters.getProperty(property);
		    tokenizeNLs = Boolean.valueOf(value);
		}
	    }
 
	    text = Textdocument.readTextDocument(inputFormat, uploadedFileBase64String, localFilePath);

	    BinaryValueManager bvm = new DefaultBinaryValueManager(context);
	    Base64BinaryDocument bvfis = null; 
	    if ("".equals(text)) {
		LOG.error("No text extracted from the word processor document!");
	    } else {
		tokenizeString(text, outputFormat);
		bvfis = Base64BinaryDocument.getInstance(bvm, Files.newInputStream(tempOutFile));
	    }
	    return bvfis;
        } catch (IOException ioe) {
	    throw new XPathException(this, "Error while reading document: " + ioe.getMessage(), ioe);
        } finally {
            context.popDocumentContext();
	    if (tempInFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempInFile);
	    }
	    if (tempOutFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempOutFile);
	    }

        }
    }

    private void tokenizeString(String text, final OutDocType outputFormat) {
	PTBTokenizer<CoreLabel> tokenizer =
	    PTBTokenizer.newPTBTokenizer(new StringReader(text), tokenizeNLs, true);
	cachedTokenizer = tokenizer;
	List<CoreLabel> tokens = tokenizer.tokenize();
	List<List<CoreLabel>> sentences = new WordToSentenceProcessor(WordToSentenceProcessor.NewlineIsSentenceBreak.TWO_CONSECUTIVE).wordsToSentences(tokens);
	Spreadsheet.createSpreadsheet(sentences, tokens.size(), outputFormat, tempOutFile, backgroundSymbol);
    }

}
