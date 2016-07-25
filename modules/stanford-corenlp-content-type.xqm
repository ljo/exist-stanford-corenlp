xquery version "3.1";

module namespace content-type = "http://exist-db.org/xquery/stanford-corenlp/content-type";

declare function content-type:get-content-type($filename as xs:string?, $content-type as xs:string?, $default as xs:string) as xs:string {
    if (empty($filename) and empty($content-type)) then 
        $default
    else 
        let $content-type-ext :=
            switch ($content-type)
            case "application/vnd.oasis.opendocument.spreadsheet" return "ods"
            case "application/vnd.oasis.opendocument.text" return "odt"
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" return "xlsx"
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" return "docx"
            case "application/vnd.ms-excel" return "xls"
            case "application/msword" return "doc"
            case "text/tab-separated-values" case "text/tsv" return "tsv"
            case "text/text" return "txt"
            case "application/gzip" return "gz"
            default return ()
        let $filename-ext := 
            substring(analyze-string($filename, "\.(\w\w\w\w?)$")//fn:match, 2)
    return
        ($content-type-ext, $filename-ext)[1]
};

declare function content-type:get-content-mimetype($content-type as xs:string) as xs:string {
    switch ($content-type)
    case "ods" return "application/vnd.oasis.opendocument.spreadsheet"
    case "odt" return "application/vnd.oasis.opendocument.text"
    case "xlsx" return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    case "docx" return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    case "xls" return "application/vnd.ms-excel"
    case "doc" return "application/msword"
    case "tsv" return "text/tab-separated-values"
    case "txt" return "text/text"
    case "gz" return "application/gzip"
    default return "application/octet-stream"
};

declare function content-type:get-output-type-from-input-type($input-type as xs:string) as xs:string {
    switch ($input-type)
    case "odt" case "ods" return "ods"
    case "docx" case "xlsx" return "xlsx"
    case "doc" case "xls" return "xls"
    case "txt" case "tsv" return "tsv"
    default return "ods"
};
