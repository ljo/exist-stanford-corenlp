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

import org.jopendocument.dom.ODPackage;
import org.jopendocument.dom.ODDocument;
import org.jopendocument.dom.text.TextDocument;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;

public class Spreadsheet {
    private final static Logger LOG = LogManager.getLogger(Spreadsheet.class);

    public enum InputDocType {ODS, XLSX, XLS, TSV, ODT, DOCX, DOC, TXT};
    public enum TextDocType {ODT, DOCX, DOC, TXT};
    public enum OutDocType {ODS, XSLX, XSL, TSV};

    public static void createSpreadsheet(List<List<CoreLabel>> sentences, int tokens, final OutDocType outputFormat, final Path tempOutFile, final String backgroundSymbol) {
	switch(outputFormat) {
	case ODS:
	    createODSSpreadsheet(sentences, tokens, tempOutFile, backgroundSymbol);
	    break;
	case XSLX:
	    createXSLXSpreadsheet(sentences, outputFormat, tempOutFile, backgroundSymbol);
	    break;
	case XSL:
	    createXSLXSpreadsheet(sentences, outputFormat, tempOutFile, backgroundSymbol);
	    break;
	case TSV:
	    createTSVSpreadsheet(sentences, tempOutFile, backgroundSymbol);
	    break;
	}
    }

    private static void createODSSpreadsheet(List<List<CoreLabel>> sentences, int tokens, final Path tempOutFile, final String backgroundSymbol) {
	SpreadSheet spreadSheet = SpreadSheet.create(1, 2, sentences.size() + tokens);

	Sheet sheet = spreadSheet.getSheet(0);

	int lineIndex = 0;
	for (List<CoreLabel> sentence : sentences) {
	    for (CoreLabel token : sentence) {
		String value = token.get(CoreAnnotations.OriginalTextAnnotation.class);
		sheet.setValueAt(value, 0, lineIndex);
		if (token.get(CoreAnnotations.AnswerAnnotation.class) == null) {
		    sheet.setValueAt(backgroundSymbol, 1, lineIndex);
		} else {
		    sheet.setValueAt(token.get(CoreAnnotations.AnswerAnnotation.class), 1, lineIndex);
		}

		lineIndex++;
	    }
	    sheet.setValueAt("", 0, lineIndex);
	    sheet.setValueAt("", 1, lineIndex);
	    lineIndex++;
	}

	try (OutputStream os = Files.newOutputStream(tempOutFile)) {
	    spreadSheet.getPackage().save(os);
	} catch (FileNotFoundException fe) {
	    LOG.error(fe);
	} catch (IOException ioe) {
	    LOG.error(ioe);
	} finally {
	    if (spreadSheet != null) {
		spreadSheet = null;
	    }
	}
    }

    private static void createXSLXSpreadsheet(List<List<CoreLabel>> sentences, OutDocType outputFormat, final Path tempOutFile, final String backgroundSymbol) {
	Workbook workbook = null;
	if (outputFormat == OutDocType.XSLX) {
	    workbook = new SXSSFWorkbook();
	} else {
	    workbook = new HSSFWorkbook();
	}
	CreationHelper creationHelper = workbook.getCreationHelper();
	org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet();
	
	Font boldFont = workbook.createFont();
	boldFont.setBoldweight(Font.BOLDWEIGHT_BOLD);
		
	// Header
	CellStyle headerStyle = workbook.createCellStyle();
	headerStyle.setFont(boldFont);
	int lineIndex = 0;
	for (List<CoreLabel> sentence : sentences) {
	    for (CoreLabel token : sentence) {
		String value = token.get(CoreAnnotations.OriginalTextAnnotation.class);
		Row row = sheet.createRow(lineIndex);
		row.createCell(0).setCellValue(creationHelper.createRichTextString(value));
		if (token.get(CoreAnnotations.AnswerAnnotation.class) == null) {
		    row.createCell(1).setCellValue(creationHelper.createRichTextString(backgroundSymbol));
		} else {
		    row.createCell(1).setCellValue(creationHelper.createRichTextString(token.get(CoreAnnotations.AnswerAnnotation.class)));
		}

		lineIndex++;
	    }
	    Row row = sheet.createRow(lineIndex);
	    row.createCell(0).setCellValue(creationHelper.createRichTextString(""));
	    row.createCell(1).setCellValue(creationHelper.createRichTextString(""));
	    lineIndex++;
	}

	try (OutputStream os = Files.newOutputStream(tempOutFile)) { 
	    workbook.write(os);
	} catch (FileNotFoundException fe) {
	    LOG.error(fe);
	} catch (IOException ioe) {
	    LOG.error(ioe);
	} finally {
	    if (workbook != null) {
		if (workbook instanceof SXSSFWorkbook) {
		    ((SXSSFWorkbook) workbook).dispose();
		} else {
		    workbook = null;
		}
	    }
	}
    }

    private static void createTSVSpreadsheet(List<List<CoreLabel>> sentences, final Path tempOutFile, final String backgroundSymbol) {
	BufferedWriter tsv = null;
	String separator = "\t";
	try {
	    tsv = Files.newBufferedWriter(tempOutFile);
	    for (List<CoreLabel> sentence : sentences) {
		for (CoreLabel token : sentence) {
		    String value = token.get(CoreAnnotations.OriginalTextAnnotation.class);
		    tsv.append("\"");
		    tsv.append(value);
		    tsv.append("\"");
		    tsv.append(separator);
		    tsv.append("\"");
		    if (token.get(CoreAnnotations.AnswerAnnotation.class) == null) {
			tsv.append(backgroundSymbol);
		    } else {
			tsv.append(token.get(CoreAnnotations.AnswerAnnotation.class));
		    }
		    tsv.append("\"");
		    tsv.append("\n");
		}
		tsv.append("\n");
	    }
	    tsv.close();
	} catch (FileNotFoundException fe) {
	    LOG.error(fe);
	} catch (IOException ioe) {
	    LOG.error(ioe);
	} finally {
	    if (tsv != null) {
		tsv = null;
	    }
	}
    }

    public static Collection<List<CoreLabel>> readSpreadsheet(final InputDocType inputFormat, BinaryValueFromBinaryString uploadedFileBase64String, final String localFilePath, final int tagCol) throws XPathException {
	Collection<List<CoreLabel>> res = null;
	if (uploadedFileBase64String == null && localFilePath == null) {
	    res = readODSSpreadsheet(uploadedFileBase64String, "/db/temp/swe-clarin/user-annotated.ods", tagCol);
	} else {
	    switch(inputFormat) {
	    case ODS:
		res = readODSSpreadsheet(uploadedFileBase64String, localFilePath, tagCol);
		break;
	    case XLSX:
		res = readXLSXSpreadsheet(uploadedFileBase64String, localFilePath, inputFormat);
		break;
	    case XLS:
		res = readXLSXSpreadsheet(uploadedFileBase64String, localFilePath, inputFormat);
		break;
	    case TSV:
		res = readTSVSpreadsheet(uploadedFileBase64String, localFilePath);
		break;
	    }
	}
	return res;
    }

    private static Collection<List<CoreLabel>> readODSSpreadsheet(final BinaryValueFromBinaryString uploadedFileBase64String, final String localFilePath, final int tagCol) throws XPathException {
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();
	SpreadSheet spreadSheet = null;

	//try (InputStream is = Files.newInputStream(tempInFile)) {
	try (InputStream is = uploadedFileBase64String != null ? uploadedFileBase64String.getInputStream() : new Resource(localFilePath).getInputStream()) {
	    spreadSheet = ODPackage.createFromStream(is, "UserAnnotatedDocument").getSpreadSheet();
	} catch (IOException ioe) {
	    throw new XPathException("Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}

	Sheet sheet = spreadSheet.getSheet(0);
	    
	for (int i = 0; i < sheet.getRowCount(); i++) {
	    CoreLabel tok = new CoreLabel();
	    String value1 = sheet.getValueAt(0, i).toString();
	    String value2 = sheet.getValueAt(1, i).toString();

	    tok.setWord(value1);
	    tok.setNER(value2);
	    tok.set(CoreAnnotations.AnswerAnnotation.class, value2);
	    if (sheet.getColumnCount() > 2) {
		String value3 = sheet.getValueAt(2, i).toString();
		if (!"".equals(value3) && tagCol > -1) {
		    tok.setTag(value3);
		}
	    }

	    if (!"".equals(value1)) {
		document.add(tok);
	    } else {
		documents.add(document);
		document = new ArrayList<>();
	    }
	}
	if (document.size() > 0) {
	    documents.add(document);
	}
	return documents;
    }

    private static Collection<List<CoreLabel>> readXLSXSpreadsheet(final BinaryValueFromBinaryString uploadedFileBase64String, final String localFilePath, final InputDocType inputFormat) throws XPathException {
	Workbook workbook = null;
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();

	// try (InputStream is = Files.newInputStream(tempInFile)) {
	try (InputStream is = uploadedFileBase64String != null ? uploadedFileBase64String.getInputStream() : new Resource(localFilePath).getInputStream()) {
	    if (inputFormat == InputDocType.XLSX) {
		workbook = new XSSFWorkbook(is);
	    } else {
		workbook = new HSSFWorkbook(is);
	    }
	} catch (FileNotFoundException fe) {
	    LOG.error(fe);
	} catch (IOException ioe) {
	    LOG.error(ioe);
	    throw new XPathException("Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}
	org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
	Row row;
	Cell cell;
	for (int rowPos = 0; rowPos <= sheet.getLastRowNum(); rowPos++) {
	    CoreLabel tok = new CoreLabel();
	    row = (Row) sheet.getRow(rowPos);
	    if (row != null) {
		for (int cellPos = 0; cellPos < row.getLastCellNum(); cellPos++) {
		    cell = row.getCell(cellPos, Row.CREATE_NULL_AS_BLANK);
		    switch (cellPos) {
		    case 0:
			if (cell != null && cell.getCellType() == Cell.CELL_TYPE_STRING) {
			    tok.setWord(cell == null ? "" : cell.getStringCellValue());
			} else if(cell != null && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
			    tok.setWord(cell == null ? "" : cell.getNumericCellValue() + "");
			}
			break;
		    case 1:
			if (cell != null && cell.getCellType() == Cell.CELL_TYPE_STRING) {
			    tok.setNER(cell == null ? "" : cell.getStringCellValue());
			    tok.set(CoreAnnotations.AnswerAnnotation.class, cell == null ? "O" : cell.getStringCellValue());
			} else if(cell != null && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
			    tok.setNER(cell == null ? "" : cell.getNumericCellValue() + "");
			    tok.set(CoreAnnotations.AnswerAnnotation.class, cell == null ? "O" : cell.getNumericCellValue() + "");
			}
			break;
		    case 2:
			if (cell != null && cell.getCellType() == Cell.CELL_TYPE_STRING) {
			    tok.setTag(cell == null ? "" : cell.getStringCellValue());
			} else if(cell != null && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
			    tok.setTag(cell == null ? "" : cell.getNumericCellValue() + "");
			}
			break;
		    default: break;
		    }
		}
	    }
	    if (row != null && !"".equals(tok.word())) {
		document.add(tok);
	    } else {
		documents.add(document);
		document = new ArrayList<>(); 
	    }
	}
	if (document.size() > 0) {
	    documents.add(document);
	}
	return documents;
    }

    private static Collection<List<CoreLabel>> readTSVSpreadsheet(final BinaryValueFromBinaryString uploadedFileBase64String, final String localFilePath) throws XPathException {
	String separator = "\t";
	String line;
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();

	//try (BufferedReader tsv = Files.newBufferedReader(tempInFile)) {
	try (BufferedReader tsv = uploadedFileBase64String != null ? new BufferedReader(new InputStreamReader(uploadedFileBase64String.getInputStream(), "UTF-8")) : new Resource(localFilePath).getBufferedReader()) {
	    while ((line = tsv.readLine()) != null) {
		CoreLabel tok = new CoreLabel();
		List<String> cells = Arrays.asList(line.split(separator));
		if (cells.size() > 0 && !"".equals(cells.get(0))) {
		    tok.setWord(cells.get(0));
		    tok.setNER(cells.get(1));
		    tok.set(CoreAnnotations.AnswerAnnotation.class, cells.get(1));
		    if (cells.size() > 2 && !"".equals(cells.get(2))) {
			tok.setTag(cells.get(2));
		    }
		    document.add(tok);
		} else {
		    documents.add(document);
		    document = new ArrayList<>();
		}
	    }
	    if (document.size() > 0) {
		documents.add(document);
	    }
	} catch (IOException ioe) {
	    LOG.error(ioe);
	    throw new XPathException("Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}
	return documents;
    }
}
