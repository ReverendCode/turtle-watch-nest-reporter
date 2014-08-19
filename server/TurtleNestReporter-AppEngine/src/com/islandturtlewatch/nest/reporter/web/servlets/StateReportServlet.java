package com.islandturtlewatch.nest.reporter.web.servlets;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.Getter;
import lombok.extern.java.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.islandturtlewatch.nest.reporter.backend.storage.ReportStore;
import com.islandturtlewatch.nest.reporter.web.servlets.ReportCsvGenerator.Column;
import com.islandturtlewatch.nest.reporter.web.servlets.ReportCsvGenerator.Path;

@Log
public class StateReportServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  // Keep this column separate so we can test against it.
  private static ReportColumn sectionColumn = new MappedSectionColumn("Beach Zone", "ref.owner_id");

  // This is the list of columns in the report, they will appear in this order.
  private static List<ReportColumn> reportColumns = ImmutableList.of(
      new MappedTimestampColumn("Date Nest Recorded", "report.timestamp_found_ms"),
      new StaticValueColumn("Escarpment >= 18 Encountered", "0"),
      new MappedColumn("ID/Label", "report.nest_number"),
      sectionColumn,
      new MappedDistanceColumn("Distance From Dune",
          "report.location.apex_to_barrier_ft", "report.location.apex_to_barrier_in"),
      new MappedDistanceColumn("Distance From MHW",
          "report.location.water_to_apex_ft", "report.location.water_to_apex_in"),
      new MappedColumn("Nest Relocated", "report.intervention.relocation.was_relocated"),
      new MappedIsPresentColumn("Nest Washed Over", "report.condition.wash_over.0.storm_name"),
      new MappedIsPresentColumn("Nest Completely or Partially Washed Out",
          "report.condition.wash_out.timestamp_ms"),
      new MappedIsPresentColumn("Nest Completely Depredated",
          "report.condition.preditation.0.timestamp_ms"),
      new MappedTimestampColumn("First Hatchling Emergence Date",
          "report.condition.hatch_timestamp_ms"),
      new MappedColumn("Hatchlings Disoriented", "report.condition.disorientation"),
      new MappedColumn("Nest Inventoried", "report.intervention.excavation.excavated"),
      new MappedTimestampColumn("Date Nest Inventoried",
          "report.intervention.excavation.timestamp_ms"),
      new MappedColumn("# of Dead Hatchlings", "report.intervention.excavation.dead_in_nest"),
      new MappedColumn("# of Live Hatchlings", "report.intervention.excavation.live_in_nest"),
      new MappedColumn("# of Empty Shells", "report.intervention.excavation.hatched_shells"),
      new MappedColumn("# of Dead Pipped", "report.intervention.excavation.dead_pipped"),
      new MappedColumn("# of Live Pipped", "report.intervention.excavation.live_pipped"),
      new MappedColumn("# of Whole Eggs", "report.intervention.excavation.whole_unhatched"),
      new MappedColumn("# of Damaged Eggs", "report.intervention.excavation.eggs_destroyed"),
      new MappedColumn("Latitude", "report.location.coordinates.lat"),
      new MappedColumn("Longitude", "report.location.coordinates.long"));

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    log.info("Generating Csv report, no auth.");
    ReportStore store = new ReportStore();
    store.init();

    response.setContentType(MediaType.CSV_UTF_8.toString());
    ReportCsvGenerator generator = new ReportCsvGenerator(new OrderedReportWriter());
    generator.addAllRows(store.getActiveReports());

    response.setHeader("content-disposition",
        "inline; filename=\"nest_reporter_state_report_" + new Date().toString() + ".csv\"");
    response.setCharacterEncoding("UTF-8");
    ServletOutputStream outputStream = response.getOutputStream();
    OutputStreamWriter writer = new OutputStreamWriter(outputStream, Charset.forName("UTF-8"));
    writer.write('\ufeff');
    writer.flush();
    generator.write(writer);
  }

  private static class ReportColumn {
    @Getter
    private final String name;
    @Getter
    private final ValueFetcher fetcher;
    private ReportColumn(String name, ValueFetcher fetcher) {
      this.name = name;
      this.fetcher = fetcher;
    }
  }

  // Returns a default value for column.
  private static class StaticValueColumn extends ReportColumn{
    private StaticValueColumn(String name, final String value) {
      super(name, new ValueFetcher() {
        @Override public String fetch(Map<Path, Column> columnMap, int rowId) {
          return value;
        }
      });
    }
  }

  private static class MappedColumn extends ReportColumn {
    private MappedColumn(String name, final String stringPath) {
      super(name, new ValueFetcher() {
        private final Path path = new Path(stringPath);
        @Override public String fetch(Map<Path, Column> columnMap, int rowId) {
          Column column = columnMap.get(path);
          Preconditions.checkNotNull(column, "Missing path: " + stringPath);
          return column.getValue(rowId);
        }
      });
    }
  }


  // Converts timestamp at path to date.
  private static class MappedTimestampColumn extends ReportColumn {
    private static DateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");
    private MappedTimestampColumn(String name, final String stringPath) {
      super(name, new ValueFetcher() {
        private final Path path = new Path(stringPath);
        @Override public String fetch(Map<Path, Column> columnMap, int rowId) {
          Column column = columnMap.get(path);
          Preconditions.checkNotNull(column, "Missing path: " + stringPath);
          if (!column.hasValue(rowId) || column.getValue(rowId).equals("0")) {
            return "";
          }
          Long timestamp = Long.parseLong(column.getValue(rowId));
          return DATE_FORMAT.format(new Date(timestamp));
        }
      });
    }
  }

  // Value will be YES if there is a value at the path.
  private static class MappedIsPresentColumn extends ReportColumn {
    private MappedIsPresentColumn(String name, final String stringPath) {
      super(name, new ValueFetcher() {
        private final Path path = new Path(stringPath);
        @Override public String fetch(Map<Path, Column> columnMap, int rowId) {
          Column column = columnMap.get(path);
          Preconditions.checkNotNull(column, "Missing path: " + stringPath);
          return column.hasValue(rowId) ? "YES" : "NO";
        }
      });
    }
  }

  // Column will read section number out of submitting user.
  private static class MappedSectionColumn extends ReportColumn {
    private static Pattern USER_PATTERN = Pattern.compile("section([0-9]+)@islandturtlewatch.com");
    private MappedSectionColumn(String name, final String stringPath) {
      super(name, new ValueFetcher() {
        private final Path path = new Path(stringPath);

        @Override
        public String fetch(Map<Path, Column> columnMap, int rowId) {
          Column column = columnMap.get(path);
          Preconditions.checkNotNull(column, "Missing path: " + stringPath);
          String user = column.getValue(rowId);
          Matcher matcher = USER_PATTERN.matcher(user);
          if (!matcher.matches()) {
            return "";
          }
          return matcher.group(1);
        }
      });
    }
  }

  // Converts values at stringPathFt and stirngPathIn to Ft.In like 2.01.
  private static class MappedDistanceColumn extends ReportColumn {
    private MappedDistanceColumn(
        String name, final String stringPathFt, final String stringPathIn) {
      super(name, new ValueFetcher() {
        private final Path pathFt = new Path(stringPathFt);
        private final Path pathIn = new Path(stringPathIn);
        @Override public String fetch(Map<Path, Column> columnMap, int rowId) {
          Column columnFt = Preconditions.checkNotNull(columnMap.get(pathFt),
              "Missing path: " + stringPathFt);
          Column columnIn = Preconditions.checkNotNull(columnMap.get(pathIn),
              "Missing path: " + stringPathIn);
          return String.format("%s.%02d", columnFt.getValue(rowId),
              columnIn.hasValue(rowId) ? Integer.parseInt(columnIn.getValue(rowId)) : 0);
        }
      });
    }
  }

  public interface ValueFetcher {
    public String fetch(Map<Path, Column> columnMap, int rowId);
  }

  private class OrderedReportWriter implements ReportCsvGenerator.ReportWriter {
    @Override
    public void writeHeader(Writer writer, Iterable<Path> columns)
        throws IOException {
      List<String> cells = new ArrayList<>();
      for (ReportColumn column : reportColumns) {
        cells.add(column.getName());
      }
      writer.append(ReportCsvGenerator.csvJoiner.join(cells)).append('\n');
    }

    @Override
    public void writeRow(Writer writer, Map<Path, Column> columnMap, int rowId)
        throws IOException {
      if (sectionColumn.getFetcher().fetch(columnMap, rowId).equals("")) {
        // If there is no section number it is a junk report.
        return;
      }

      List<String> cells = new ArrayList<>();
      for (ReportColumn column : reportColumns) {
        cells.add(column.getFetcher().fetch(columnMap, rowId));
      }
      writer.append(ReportCsvGenerator.csvJoiner.join(cells)).append('\n');
    }

  }
}