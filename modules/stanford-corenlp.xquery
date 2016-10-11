xquery version "3.0";

import module namespace corenlp="http://exist-db.org/xquery/stanford-corenlp";

import module namespace content-type = "http://exist-db.org/xquery/stanford-corenlp/content-type"
    at "./stanford-corenlp-content-type.xqm";
    
declare namespace tei = "http://www.tei-c.org/ns/1.0";
declare namespace xhtml = "http://www.w3.org/1999/xhtml";
let $req-mode := request:get-parameter("req-mode", "tokenize")
let $req-content-type := request:get-attribute("Content-Type")
let $result :=
    if ($req-mode eq "tokenize") then 
    let $tokenizer-input-format := content-type:get-content-type(request:get-uploaded-file-name("tokenize-wp-doc"), $req-content-type, "odt")
    let $tokenizer-output-format := content-type:get-output-type-from-input-type($tokenizer-input-format)
    let $tokenizer-config := 
    <parameters>
        <param name="inputFormat" value="{$tokenizer-input-format}" />
        <param name="outputFormat" value="{$tokenizer-output-format}" />
        <param name="backgroundSymbol" value="O" />
        <param name="tokenizeNLs" value="false" />
    </parameters>
    return 
      response:stream-binary(corenlp:tokenize-wp-doc("edu.stanford.nlp.process.PTBTokenizer", $tokenizer-config, request:get-uploaded-file-data("tokenize-wp-doc")), content-type:get-content-mimetype($tokenizer-output-format), "user-tokenized-two-column." || $tokenizer-output-format)

    else if ($req-mode eq "train") then
      let $train-input-format := content-type:get-content-type(request:get-uploaded-file-name("train-classifier-spreadsheet-doc"), $req-content-type, "ods")
      let $train-output-format := "ser.gz"
      let $train-config := 
    <parameters>
        <param name="inputFormat" value="{$train-input-format}" />
        <param name="outputFormat" value="{$train-output-format}" />
        <param name="backgroundSymbol" value="O" />
        <param name="wordCol" value="0" />
        <param name="answerCol" value="1" />
    </parameters>
    return
      response:stream-binary(corenlp:train-classifier-spreadsheet-doc("edu.stanford.nlp.ie.crf.CRFClassifier", $train-config, request:get-uploaded-file-data("train-classifier-spreadsheet-doc")), "application/octet-stream", "user-crf-3class-model." || $train-output-format)

    else if ($req-mode eq "classify") then 
    let $classify-input-format := content-type:get-content-type(request:get-uploaded-file-name("classify-wp-doc"), $req-content-type, "odt")
    let $classify-output-format := content-type:get-output-type-from-input-type($classify-input-format)
    let $classify-config := 
    <parameters>
        <param name="inputFormat" value="{$classify-input-format}" />
        <param name="outputFormat" value="{$classify-output-format}" />
        <param name="classifierGZipped" value="{ends-with(request:get-uploaded-file-name("classify-classifier"), ".gz")}" />
        <param name="backgroundSymbol" value="O" />
        <param name="tokenizeNLs" value="false" />
    </parameters>
    return 
      response:stream-binary(corenlp:classify-wp-doc(request:get-uploaded-file-data("classify-classifier"), $classify-config, request:get-uploaded-file-data("classify-wp-doc")), content-type:get-content-mimetype($classify-output-format), "user-classified-two-column." || $classify-output-format)
    else 
    <div>Unknown mode {$req-mode} for document(s) {(request:get-uploaded-file-name("tokenize-wp-doc"), request:get-uploaded-file-name("train-classifier-spreadsheet-doc"), request:get-uploaded-file-name("classify-wp-doc"), request:get-uploaded-file-name("classify-classifier"))} with content type {$req-content-type} and short-type of 
{(content-type:get-content-type(request:get-uploaded-file-name("tokenize-wp-doc"), $req-content-type, ""),
content-type:get-content-type(request:get-uploaded-file-name("train-classifier-spreadsheet-doc"), $req-content-type, ""),
content-type:get-content-type(request:get-uploaded-file-name("classify-classifier"), $req-content-type, ""),
content-type:get-content-type(request:get-uploaded-file-name("classify-wp-doc"), $req-content-type, ""))}</div>
return $result
