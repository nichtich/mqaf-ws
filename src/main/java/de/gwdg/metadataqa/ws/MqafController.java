package de.gwdg.metadataqa.ws;

import de.gwdg.metadataqa.api.calculator.CalculatorFacade;
import de.gwdg.metadataqa.api.cli.InputFormat;
import de.gwdg.metadataqa.api.cli.RecordFactory;
import de.gwdg.metadataqa.api.configuration.MeasurementConfiguration;
import de.gwdg.metadataqa.api.configuration.schema.Rule;
import de.gwdg.metadataqa.api.interfaces.MetricResult;
import de.gwdg.metadataqa.api.io.reader.RecordReader;
import de.gwdg.metadataqa.api.io.reader.XMLRecordReader;
import de.gwdg.metadataqa.api.io.writer.ResultWriter;
import de.gwdg.metadataqa.api.json.DataElement;
import de.gwdg.metadataqa.api.rule.RuleCheckingOutputType;
import de.gwdg.metadataqa.api.schema.Schema;

import de.gwdg.metadataqa.ws.dao.InputParameters;
import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@RestController
public class MqafController {

  private static final Logger logger = Logger.getLogger(MqafController.class.getCanonicalName());

  @Value("${spring.application.name}")
  String appName;

  @Autowired
  private MqafConfiguration mqafConfiguration;

  @GetMapping("/classpath")
  public String getClassPath() {
    return System.getProperty("java.class.path");
  }

  @GetMapping("/")
  public String indexPage() {
    return String.format("This is %s", appName);
  }

  @PostMapping("/validate")
  public ResponseEntity<String> validate(
    @RequestParam(value = "schemaContent", defaultValue = "") String schemaContent,
    @RequestParam(value = "schemaFile", defaultValue = "") String schemaFile,
    @RequestParam(value = "schemaFormat", defaultValue = "") String schemaFormat,
    @RequestParam(value = "headers", defaultValue = "") String headers,
    @RequestParam(value = "measurementsContent", defaultValue = "") String measurementsContent,
    @RequestParam(value = "measurementsFile", defaultValue = "") String measurementsFile,
    @RequestParam(value = "measurementsFormat", defaultValue = "") String measurementsFormat,
    @RequestParam(value = "inputFile", defaultValue = "") String inputFile,
    @RequestParam(value = "inputFormat", defaultValue = "") String inputFormat,
    @RequestParam(value = "gzip", defaultValue = "false") boolean gzip,
    @RequestParam(value = "outputFormat", defaultValue = "ndjson") String outputFormat,
    @RequestParam(value = "output", defaultValue = "") String outputFile,
    @RequestParam(value = "recordAddress", defaultValue = "") String recordAddress,
    Model model
  ) {
    try {
      InputParameters inputParameters = new InputParameters(mqafConfiguration);
      logger.info(String.format("gzip: %s", gzip));

      Schema schema = inputParameters.createSchema(schemaContent, getInputFilePath(schemaFile), schemaFormat);

      // initialize config
      MeasurementConfiguration measurementConfig = inputParameters.createMeasurementConfiguration(
        measurementsContent, getInputFilePath(measurementsFile), measurementsFormat);
      logger.info(String.format("isUniquenessMeasurementEnabled: %s", measurementConfig.isUniquenessMeasurementEnabled()));

      // Set the fields supplied by the command line to extractable fields
      if (!headers.equals("")) {
        String[] headersList = StringUtils.split(headers, ",");
        for (String h : headersList) {
          schema.addExtractableField(h, schema.getPathByLabel(h).getPath());
        }
      }

      // initialize calculator
      CalculatorFacade calculator = new CalculatorFacade(measurementConfig);
      // set the schema which describes the source
      calculator.setSchema(schema);

      // initialize input
      InputFormat inputFormatEnum = InputFormat.byCode(inputFormat);
      RecordReader inputReader = RecordFactory.getRecordReader(getInputFilePath(inputFile), calculator, gzip, inputFormatEnum);

      // initialize output
      // String outFormat = cmd.getOptionValue(OUTPUT_FORMAT, NDJSON);
      // write to std out if no file was given
      outputFile = getOutputFilePath(outputFile);
      inputParameters.setOutputFilePath(outputFile);
      inputParameters.saveInputParameters();

      ResultWriter outputWriter = !outputFile.equals("")
        ? RecordFactory.getResultWriter(outputFormat, outputFile)
        : RecordFactory.getResultWriter(outputFormat);

      if (recordAddress.equals(""))
        recordAddress = null;
      if (inputReader instanceof XMLRecordReader && recordAddress != null)
        ((XMLRecordReader)inputReader).setRecordAddress(recordAddress);

      long counter = 0;
      List<String> header = calculator.getHeader();
      logger.info(StringUtils.join(header, " -- "));
      outputWriter.writeHeader(header);

      while (inputReader.hasNext()) {
        Map<String, List<MetricResult>> measurement = inputReader.next();
        outputWriter.writeResult(measurement);

        // update process
        counter++;
        if (counter % 50 == 0) {
          logger.info(String.format("Processed %s records. ", counter));
        }
      }
      logger.info(String.format("Assessment completed successfully with %s records. ", counter));
      outputWriter.close();

      postProcess(inputParameters);

      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.set("Content-Type", MediaType.APPLICATION_JSON.toString());

      return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .headers(responseHeaders)
        .body("{\"result\": 1}");

    } catch (Exception e) {
      e.printStackTrace();
      // logger.severe(String.format("Assessment failed with %s records. ", counter));
      logger.severe(String.format("Assessment failed"));
      logger.severe(e.getClass() + " " + e.getMessage());

      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.set("Content-Type", MediaType.APPLICATION_JSON.toString());

      return ResponseEntity.internalServerError()
        .contentType(MediaType.APPLICATION_JSON)
        .headers(responseHeaders)
        .body(String.format("{result: %s}", e.getMessage()));
    }
  }

  private void postProcess(InputParameters inputParameters) throws IOException {
    logger.info("postProcess()");
    createDatabaseDefinition(inputParameters);
    // sudo -u www-data
    List<String> commands = List.of(
      String.format("php /opt/metadata-qa/scripts/csv2sql.php --csvFile %s --tableName 'output' --outputDir %s",
        inputParameters.getOutputFilePath(), inputParameters.getOutputDir()),
      /*
      String.format("mysql -h database -u mqaf -pmqaf mqaf < %s/output-definition.sql",
        inputParameters.getOutputDir()),
      String.format("mysql -h database -u mqaf -pmqaf mqaf < %s/output.sql",
        inputParameters.getOutputDir()),
       */
      String.format("Rscript /opt/metadata-qa/scripts/analyse-output.R --csv %s --outputDir %s --fields %s -v",
        inputParameters.getOutputFilePath(), inputParameters.getOutputDir(),
        StringUtils.join(inputParameters.getRuleColumns(), ",")),
      String.format("/opt/metadata-qa/scripts/postprocess.sh"
          + " --outputFilePath %s"
          + " --outputDir %s"
          + " --inputDir %s"
          + " --ruleColumns %s",
        inputParameters.getOutputFilePath(), inputParameters.getOutputDir(), inputParameters.getInputDir(),
        StringUtils.join(inputParameters.getRuleColumns(), ",")
      )
      /*
       */
    );

    Process process = null;
    try {
      for (String command : commands) {
        logger.info(command);
        process = Runtime.getRuntime().exec(command);
        TimeUnit.SECONDS.sleep(1);
        StringBuilder textBuilder = new StringBuilder();
        try (Reader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          int c = 0;
          while ((c = reader.read()) != -1) {
            textBuilder.append((char) c);
          }
        }
        logger.info(textBuilder.toString());
        logger.info("exitValue: " + process.exitValue());
        // if (process.exitValue() != 0) {
          InputStream error = process.getErrorStream();
          textBuilder = new StringBuilder();
          try (Reader reader = new BufferedReader(new InputStreamReader(error, StandardCharsets.UTF_8))) {
            int c = 0;
            while ((c = reader.read()) != -1) {
              textBuilder.append((char) c);
            }
          }
          if (!textBuilder.isEmpty())
            logger.warning(textBuilder.toString());
        // }
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    logger.info("/postProcess()");
  }

  private static void createDatabaseDefinition(InputParameters inputParameters) {
    RuleCheckingOutputType outPutType = inputParameters.getMeasurementConfig().getRuleCheckingOutputType();
    List<String> ruleColumns = new ArrayList<>();
    logger.info("outPutType: " + outPutType.toString());
    Map<String, String> mapping = new LinkedHashMap<>();
    // List<String> fields = new ArrayList<>();
    for (DataElement dataElement : inputParameters.getSchema().getPaths()) {
      if (dataElement.isExtractable()) {
        mapping.put(dataElement.getLabel(), "VARCHAR(255)");
        // logger.info(dataElement.getLabel());
      }
      List<Rule> rules = dataElement.getRules();
      if (rules != null) {
        for (Rule rule : rules) {
          if (outPutType.equals(RuleCheckingOutputType.STATUS)) {
            mapping.put(rule.getId() + "_status", "BOOLEAN");
            ruleColumns.add(rule.getId().toLowerCase() + "_status");
          } else if (outPutType.equals(RuleCheckingOutputType.SCORE)) {
            mapping.put(rule.getId() + "_score", "INTEGER");
            ruleColumns.add(rule.getId().toLowerCase() + "_score");
          } else {
            mapping.put(rule.getId() + "_status", "BOOLEAN");
            ruleColumns.add(rule.getId().toLowerCase() + "_status");
            mapping.put(rule.getId() + "_score", "INTEGER");
            ruleColumns.add(rule.getId().toLowerCase() + "_score");
          }
          logger.info(rule.getId());
        }
      }
    }
    mapping.put("rulecatalog_score", "INTEGER");
    ruleColumns.add("rulecatalog_score");
    inputParameters.setRuleColumns(ruleColumns);

    try (PrintWriter out = new PrintWriter(inputParameters.getOutputDir() + "/output-definition.sql")) {
      out.println(createDatabaseDefinitionSQL(mapping));
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private static String createDatabaseDefinitionSQL(Map<String, String> mapping) {
    List<String> lines = new ArrayList<>();
    lines.add(String.format("CREATE TABLE %s (", "output"));
    List<String> fields = new ArrayList<>();
    for (Map.Entry<String, String> entry : mapping.entrySet())
      fields.add(String.format("  `%s` %s", entry.getKey(), entry.getValue()));
    lines.add(StringUtils.join(fields, ",\n"));
    lines.add(");");
    return StringUtils.join(lines, "\n");
  }

  private String getInputFilePath(String inputFile) {
    return getPath(mqafConfiguration.getInputDir(), inputFile);
  }

  private String getOutputFilePath(String outputFile) {
    return getPath(mqafConfiguration.getOutputDir(), outputFile);
  }

  private static String getPath(String dir, String file) {
    if (StringUtils.isNoneBlank(dir) && (new File(dir)).exists()) {
      String separator = (dir.endsWith("/")) ? "" : "/";
      file = dir + separator + file;
    }
    return file;
  }

}
