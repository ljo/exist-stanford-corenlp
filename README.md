exist-stanford-corenlp
==================

Integrate the Stanford CoreNLP annotation pipeline library into eXist-db.

Demo and documentation are included in the package.

## Compile and install

1. clone the github repository: https://github.com/ljo/exist-stanford-corenlp
2. edit local.build.properties and set exist.dir to point to your eXist install directory
3. call "ant" in the directory to create a .xar
4. upload the xar into eXist using the dashboard

## Functions
The module currently provides support to create a Named Entity Recognition (NER) classifier model and run some of the pipeline tools, including the NER classifier, on your documents.

See [The main page of the app](http://localhost:8080/exist/apps/stanford-corenlp/index.html "The app main page") once installed.
