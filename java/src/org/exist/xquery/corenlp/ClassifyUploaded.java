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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.WordToSentenceProcessor;
import edu.stanford.nlp.sequences.SeqClassifierFlags;
import edu.stanford.nlp.sequences.ColumnDocumentReaderAndWriter;

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
import org.apache.xmlbeans.XmlException;

import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.security.PermissionDeniedException;
import org.exist.util.Configuration;
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

public class ClassifyUploaded extends BasicFunction {
    private final static Logger LOG = LogManager.getLogger(ClassifyUploaded.class);

    public final static FunctionSignature signatures[] = {
            new FunctionSignature(
                new QName("classify-wp-doc", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Mark up named entities in the provided wordprocessing document. Returns the document with annotatations inline.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("uploaded-classifier", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                        "The uploaded classifier to use. If no file is posted in the request you need to provide localClassifierFilePath in the configuration parameter."),
		    new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                        "The annotation configuration, e.g. &lt;parameters&gt;&lt;param name='inputFormat' value='ods'/&gt;&lt;param name='backgroundSymbol' value='O'/&gt;&lt;param name='wordCol' value='0'/&gt;&lt;param name='answerCol' value='1'/&gt;&lt;param name='tagCol' value='2'/&gt;&lt;/parameters&gt;. Available input formats odt (default), docx, doc or txt."),
                    new FunctionParameterSequenceType("uploaded-file", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                        "The uploaded file with your text to annotate. If no file is posted in the request you need to provide localFilePath in the configuration parameter.")

                },
                new FunctionReturnSequenceType(Type.ITEM, Cardinality.EXACTLY_ONE,
                        "The annotated wordprocessing document")
            ),
            new FunctionSignature(
                new QName("classify-spreadsheet-doc", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Mark up named entities in the provided spreadsheet document. Returns the document with annotatations in a new column or in a three-column-fashion (token, annotation, intermediary text).",
                new SequenceType[] {
                    new FunctionParameterSequenceType("uploaded-classifier", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                        "The uploaded classifier to use. If no file is posted in the request you need to provide localClassifierFilePath in the configuration parameter."),
		    new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                        "The classification configuration, e.g. &lt;parameters&gt;&lt;param name='inputFormat' value='ods'/&gt;&lt;param name='backgroundSymbol' value='O'/&gt;&lt;param name='wordCol' value='0'/&gt;&lt;param name='answerCol' value='1'/&gt;&lt;param name='tagCol' value='2'/&gt;&lt;/parameters&gt;. Available input formats ods (default), xlsx, xls or tsv."),
                    new FunctionParameterSequenceType("uploaded-file", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                        "The uploaded spreadsheet document with your text to annotate. If no file is posted in the request you need to provide localFilePath in the configuration parameter.")
                },
                new FunctionReturnSequenceType(Type.ITEM, Cardinality.EXACTLY_ONE,
                        "The annotated spreadsheet document")
            )
    };

    private Path tempInFile = null;
    private Path tempClassifierFile = null;
    private Path tempOutFile = null;
    private BinaryValueFromBinaryString uploadedFileBase64String = null;
    private BinaryValueFromBinaryString uploadedClassifierFileBase64String = null;
    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();
    private InputDocType inputFormat;
    private OutDocType outputFormat = OutDocType.ODS;
    private String classifierClassPath = null;
    private String backgroundSymbol = "O";
    private String localFilePath = null;
    private int wordCol = 0;
    private int answerCol = 1;
    private int tagCol = -1;
    private int tokenCount = 0;
    private boolean tokenizeNLs = false;
    private boolean classifierGZipped = true;

    public ClassifyUploaded(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        context.pushDocumentContext();
        try {
	    Collection<List<CoreLabel>> documents;
	    if (!args[0].isEmpty()) {
		uploadedClassifierFileBase64String = new BinaryValueFromBinaryString(new Base64BinaryValueType(), args[0].getStringValue());
	    }

	    if (!args[1].isEmpty()) {
		parameters = ParametersExtractor.parseParameters(((NodeValue)args[1].itemAt(0)).getNode());
	    }

	    if (!args[2].isEmpty()) {
		uploadedFileBase64String = new BinaryValueFromBinaryString(new Base64BinaryValueType(), args[2].getStringValue());
	    }
	    tempInFile = TemporaryFileManager.getInstance().getTemporaryFile();
	    tempClassifierFile = TemporaryFileManager.getInstance().getTemporaryFile();
	    tempOutFile = TemporaryFileManager.getInstance().getTemporaryFile();

	    inputFormat = isCalledAs("classify-spreadsheet-doc") == true ? InputDocType.ODS : InputDocType.ODT;

	    for (String property : parameters.stringPropertyNames()) {
		if ("inputFormat".equals(property)) {
		    String value = parameters.getProperty(property);
		    if (isCalledAs("classify-spreadsheet-doc")) {
			if ("ods".equals(value)) {
			    inputFormat = InputDocType.ODS;
			} else if ("xlsx".equals(value)) {
			    inputFormat = InputDocType.XLSX;
			} else if ("xls".equals(value)) {
			    inputFormat = InputDocType.XLS;
			} else if ("tsv".equals(value)) {
			    inputFormat = InputDocType.TSV;
			}
		    } else if (isCalledAs("classify-wp-doc")) {
			if ("odt".equals(value)) {
			    inputFormat = InputDocType.ODT;
			} else if ("docx".equals(value)) {
			    inputFormat = InputDocType.DOCX;
			} else if ("doc".equals(value)) {
			    inputFormat = InputDocType.DOC;
			} else if ("txt".equals(value)) {
			    inputFormat = InputDocType.TXT;
			}
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
		} else if ("wordCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    wordCol = Integer.valueOf(value);
		} else if ("answerCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    answerCol = Integer.valueOf(value);
		} else if ("tagCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    tagCol = Integer.valueOf(value);
		} else if ("classifierGZipped".equals(property)) {
		    String value = parameters.getProperty(property);
		    classifierGZipped = Boolean.valueOf(value);
		} else if ("tokenizeNLs".equals(property)) {
		    String value = parameters.getProperty(property);
		    tokenizeNLs = Boolean.valueOf(value);
		}
	    }
 
	    documents = isCalledAs("classify-spreadsheet-doc") == true ? Spreadsheet.readSpreadsheet(inputFormat, uploadedFileBase64String, localFilePath, tagCol) : tokenizeString(Textdocument.readTextDocument(inputFormat, uploadedFileBase64String, localFilePath));

	    BinaryValueManager bvm = new DefaultBinaryValueManager(context);
	    Base64BinaryDocument bvfis = null;
	    if (documents.isEmpty()) {
		LOG.error("No annotated text extracted from the spreadsheet/wordprocessing document!");
		throw new XPathException(this, "No annotated text extracted from the spreadsheet/wordprocessing document!");
	    } else {
		classifyText(documents, outputFormat);
		bvfis = Base64BinaryDocument.getInstance(bvm, Files.newInputStream(tempOutFile));
	    }
	    return bvfis;
        } catch (IOException ioe) {
	    throw new XPathException(this, "Error while reading text document: " + ioe.getMessage(), ioe);
        } finally {
            context.popDocumentContext();
	    if (tempInFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempInFile);
	    }
	    if (tempClassifierFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempClassifierFile);
	    }
	    if (tempOutFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempOutFile);
	    }

        }
    }

    private void classifyText(Collection<List<CoreLabel>> documents, OutDocType outputFormat) throws XPathException {
        List<List<CoreLabel>> sentences = new ArrayList<>();

	try (InputStream is = uploadedClassifierFileBase64String.getInputStream()) {
	    CRFClassifier classifier;
	    if (classifierGZipped) {
		classifier = CRFClassifier.getClassifier(new GZIPInputStream(is));
	    } else {
		classifier = CRFClassifier.getClassifier(is);
	    }

	    for (List<CoreLabel> document : documents) {
		List<CoreLabel> out = classifier.classify(document);
		sentences.add(out);
	    }
	} catch (IOException e) {
	    throw new XPathException(this, "Error while reading classifier resource: " + e.getMessage(), e);
	} catch (ClassNotFoundException e) {
	    throw new XPathException(this, "Error while reading classifier resource: " + e.getMessage(), e);
	}
	Spreadsheet.createSpreadsheet(sentences, tokenCount, outputFormat, tempOutFile, backgroundSymbol);
    }

    private Collection<List<CoreLabel>> tokenizeString(String text) {
	PTBTokenizer<CoreLabel> tokenizer =
	    PTBTokenizer.newPTBTokenizer(new StringReader(text), tokenizeNLs, true);
	List<CoreLabel> tokens = tokenizer.tokenize();
	tokenCount = tokens.size();
	List<List<CoreLabel>> sentences = new WordToSentenceProcessor(WordToSentenceProcessor.NewlineIsSentenceBreak.TWO_CONSECUTIVE).wordsToSentences(tokens);
        return sentences;
    }

}
