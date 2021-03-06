package com.islandturtlewatch.nest.reporter.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.islandturtlewatch.nest.data.ReportProto.Report;
import com.islandturtlewatch.nest.data.ReportProto.ReportRef;
import com.islandturtlewatch.nest.data.ReportProto.ReportWrapper;
import com.islandturtlewatch.nest.reporter.data.LocalDataStore.StorageDefinition.ImagesTable;
import com.islandturtlewatch.nest.reporter.data.LocalDataStore.StorageDefinition.ReportsTable;
import com.islandturtlewatch.nest.reporter.util.Sql;
import com.islandturtlewatch.nest.reporter.util.Sql.Column;
import com.islandturtlewatch.nest.reporter.util.Sql.Column.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import lombok.Cleanup;

import static com.islandturtlewatch.nest.reporter.util.Sql.and;
import static com.islandturtlewatch.nest.reporter.util.Sql.isFalse;
import static com.islandturtlewatch.nest.reporter.util.Sql.isTrue;
import static com.islandturtlewatch.nest.reporter.util.Sql.whereEquals;
import static com.islandturtlewatch.nest.reporter.util.Sql.whereStringEquals;

/**
 * Responsible for managing local version of data on all reports and images.
 *
 * If changes are made to anything in this class run LocalDataStoreTest. It is vital these tests
 * pass and all data is safely stored.
 */
public class LocalDataStore {
  private static final String TAG = LocalDataStore.class.getCanonicalName();
  private final StorageDefinition.DbHelper storageHelper;

  public LocalDataStore(Context context) {
    storageHelper = new StorageDefinition.DbHelper(context);
  }

  //TODO(Dwenzel): This could also be a good target for optimization
  public int activeReportCount() {
    return listActiveReportsWithDuplicates().size();
  }

  public ImmutableList<CachedReportWrapper> listActiveReportsWithDuplicates() {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();
    @Cleanup Cursor cursor = getActiveReportsCursor(db);

    ArrayList<CachedReportWrapper> output = new ArrayList<>();

    while (cursor.moveToNext()) {
      output.add(CachedReportWrapper.from(cursor));
    }
    output = duplicatePFCs(output);

    Collections.sort(output, new Comparator<CachedReportWrapper>() {
      @Override public int compare(CachedReportWrapper lhs, CachedReportWrapper rhs) {


        if (lhs.getReport().hasNestNumber() && !rhs.getReport().hasNestNumber()) {
          return -1;
        } else if (!lhs.getReport().hasNestNumber() && rhs.getReport().hasNestNumber()) {
          return 1;
        }
        if (lhs.getPossibleFalseCrawlDuplicate() && !rhs.getPossibleFalseCrawlDuplicate()) {
          return -1;
        } else if (!lhs.getPossibleFalseCrawlDuplicate() && rhs.getPossibleFalseCrawlDuplicate()) {
          return 1;
        }
          int fcDiff = lhs.getReport().getFalseCrawlNumber() - rhs.getReport().getFalseCrawlNumber();
          if (fcDiff != 0) {
            return fcDiff;
          }

        int pfcDiff = lhs.getReport().getPossibleFalseCrawlNumber() - rhs.getReport().getPossibleFalseCrawlNumber();
        if (pfcDiff != 0) {
          return pfcDiff;
        }
          int diff = lhs.getReport().getNestNumber() - rhs.getReport().getNestNumber();
          if (diff != 0) {
            return diff;
          }

            return 0;
      }});

    return ImmutableList.copyOf(output);
  }
  public ArrayList<CachedReportWrapper> duplicatePFCs(ArrayList<CachedReportWrapper> arr) {
    ArrayList<CachedReportWrapper> tempArray = new ArrayList<>();
    for (CachedReportWrapper report: arr) {

      if (report.getReport().getPossibleFalseCrawl()) {
        CachedReportWrapper temp = new CachedReportWrapper();
        temp.setReport(report.getReport());
        temp.setLocalId(report.getLocalId());
        temp.setPossibleFalseCrawlDuplicate(true);
        tempArray.add(temp);
      }
    }
    tempArray.addAll(arr);
    return tempArray;
  }

  /**
   * Return all reports that have local changes that have not been synced to the server.
   */
  public ImmutableList<CachedReportWrapper> listUnsyncedReports() {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();
    @Cleanup Cursor cursor = db.query(
        ReportsTable.TABLE_NAME, // table name
        CachedReportWrapper.requiredColumns, // cols to select
        isFalse(ReportsTable.COLUMN_SYNCED), // where
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null); // don't sort
      ArrayList<CachedReportWrapper> output = new ArrayList<>();
    while (cursor.moveToNext()) {
      output.add(CachedReportWrapper.from(cursor));
    }
    return ImmutableList.copyOf(output);
  }

  /**
   * Get file names of images that have local changes the server hasn't seen.
   */
  public Set<String> getUnsycnedImageFileNames() {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();

    @Cleanup Cursor imageCursor = db.query(
        ImagesTable.TABLE_NAME, // table name
        new String[]{ImagesTable.COLUMN_LOCAL_REPORT_ID.name,
            ImagesTable.COLUMN_FILE_NAME.name}, // cols to select
        isFalse(ImagesTable.COLUMN_SYNCED), // where
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null); // don't sort

    ImmutableList<String> unsynchedReports =
        Sql.getAllString(imageCursor, ImagesTable.COLUMN_FILE_NAME);
    return ImmutableSet.copyOf(unsynchedReports);
  }

  public boolean hasImage(long localReportId, String filename) {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();

    @Cleanup Cursor imageCursor = db.query(
        ImagesTable.TABLE_NAME, // table name
        null, // cols to select
        and(whereEquals(ImagesTable.COLUMN_LOCAL_REPORT_ID, localReportId),
            whereStringEquals(ImagesTable.COLUMN_FILE_NAME, filename)), // where
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null); // don't sort
    return imageCursor.getCount() > 0;
  }

  public CachedReportWrapper getReport(long localId) {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();

    @Cleanup Cursor cursor = db.query(
        ReportsTable.TABLE_NAME,
        CachedReportWrapper.requiredColumns,
        ReportsTable.keyEquals(localId),
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null // don't sort
        );
    Preconditions.checkArgument(cursor.moveToFirst(), "Failed to find report for:" + localId);
    return CachedReportWrapper.from(cursor);
  }

  public Long getLocalReportId(long reportId) {
    @Cleanup SQLiteDatabase db = storageHelper.getReadableDatabase();

    @Cleanup Cursor cursor = db.query(
        ReportsTable.TABLE_NAME, // table name
        new String[]{ReportsTable.COLUMN_LOCAL_ID.name}, // cols to select
        whereEquals(ReportsTable.COLUMN_REPORT_ID, reportId), // where
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null); // don't sort

    Preconditions.checkArgument(cursor.moveToFirst(), "Failed to find local id for report id:"
        + reportId);
    return Sql.getLong(cursor, ReportsTable.COLUMN_LOCAL_ID);
  }

  /**
   * Saves local changes to a report.
   */
  public void saveReport(long localId, Report report) {
    Log.d(TAG, "saving report " + localId);
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_REPORT.name, report.toByteArray());
    values.put(ReportsTable.COLUMN_SYNCED.name, false);
    values.put(ReportsTable.COLUMN_TS_LOCAL_UPDATE.name, System.currentTimeMillis());

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        ReportsTable.keyEquals(localId),
        null);
    Preconditions.checkArgument(numberUpdated == 1,
        "Save should update one row not " + numberUpdated);
  }

  /**
   * Marks a report as having local changes.
   */
  public void setReportUnsynced(long localId) {
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_SYNCED.name, false);
    values.put(ReportsTable.COLUMN_TS_LOCAL_UPDATE.name, System.currentTimeMillis());

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        ReportsTable.keyEquals(localId),
        null);
    Preconditions.checkArgument(numberUpdated == 1,
        "set unscyned should update one row not " + numberUpdated);
  }

  /**
   * Saves updates from server.
   *
   * Requires setServerSideData to have been called for existing report, else we will add a new row.
   */
  public void updateFromServer(ReportWrapper reportWrapper) {
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_ACTIVE.name, reportWrapper.getActive());
    values.put(ReportsTable.COLUMN_REPORT_ID.name, reportWrapper.getRef().getReportId());
    values.put(ReportsTable.COLUMN_VERSION.name, reportWrapper.getRef().getVersion());
    values.put(ReportsTable.COLUMN_REPORT.name, reportWrapper.getReport().toByteArray());
    values.put(ReportsTable.COLUMN_SYNCED.name, true);

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        whereEquals(ReportsTable.COLUMN_REPORT_ID, reportWrapper.getRef().getReportId()),
        null);
    if (numberUpdated < 1) {
      // We don't have this report yet, add it.
      values.put(ReportsTable.COLUMN_TS_LOCAL_ADD.name, System.currentTimeMillis());
      long result = db.insert(ReportsTable.TABLE_NAME, null, values);
      Preconditions.checkArgument(result != -1, "Failed to insert new row");
    }
  }

  /**
   * Should be called after communicating with the server to sync the local copies of server-side
   * information.
   */
  public void setServerSideData(long localId, ReportRef ref) {
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_REPORT_ID.name, ref.getReportId());
    values.put(ReportsTable.COLUMN_VERSION.name, ref.getVersion());
    values.put(ReportsTable.COLUMN_SYNCED.name, true);

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        ReportsTable.keyEquals(localId),
        null);
    Preconditions.checkArgument(numberUpdated == 1,
        "setServerSideData should update one row not " + numberUpdated);
  }

  public void markSynced(long localId) {
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_SYNCED.name, true);

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        ReportsTable.keyEquals(localId),
        null);
    Preconditions.checkArgument(numberUpdated == 1,
        "markSynced should update one row not " + numberUpdated);
  }

  /**
   * Creates empty report record and returns the local id.
   * @return local_id local identifier of a report, not the same as report_id on server.
   */
  public CachedReportWrapper createReport() {
    int nestNumber = getHighestNestNumber() + 1;
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_REPORT.name,
        Report.newBuilder().setNestNumber(nestNumber).build().toByteArray());
    values.put(ReportsTable.COLUMN_ACTIVE.name, true);
    values.put(ReportsTable.COLUMN_TS_LOCAL_UPDATE.name, System.currentTimeMillis());
    values.put(ReportsTable.COLUMN_TS_LOCAL_ADD.name, System.currentTimeMillis());

    long localId = db.insert(ReportsTable.TABLE_NAME, null, values);
    Preconditions.checkArgument(localId != -1, "Failed to insert new row");
    return getReport(localId);
  }

  public void addImage(long localReportId, String fileName, long timestamp) {
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ImagesTable.COLUMN_LOCAL_REPORT_ID.name, localReportId);
    values.put(ImagesTable.COLUMN_FILE_NAME.name, fileName);
    values.put(ImagesTable.COLUMN_TS_LOCAL_UPDATE.name, timestamp);
    values.put(ImagesTable.COLUMN_TS_LOCAL_ADD.name, System.currentTimeMillis());

    long imageId = db.insert(ImagesTable.TABLE_NAME, null, values);
    Preconditions.checkArgument(imageId != -1, "Failed to insert new image");
  }

  public void addImageAndInvalidateReport(long localReportId, String fileName, long timestamp) {
    addImage(localReportId, fileName, timestamp);
    this.setReportUnsynced(localReportId);
  }

  /**
   * Delete report.
   */
  public void deleteReport(long localId) {
    Log.d(TAG, "deleting report " + localId);
    @Cleanup SQLiteDatabase db = storageHelper.getWritableDatabase();

    ContentValues values = new ContentValues();
    values.put(ReportsTable.COLUMN_DELETED.name, true);
    values.put(ReportsTable.COLUMN_SYNCED.name, false);
    values.put(ReportsTable.COLUMN_TS_LOCAL_UPDATE.name, System.currentTimeMillis());

    int numberUpdated = db.update(ReportsTable.TABLE_NAME,
        values,
        ReportsTable.keyEquals(localId),
        null);
    Preconditions.checkArgument(numberUpdated == 1,
        "delete should update one row not " + numberUpdated);
  }

  /**
   * Gets the highest nest number in any active, non-deleted reports.
   */
  public int getHighestNestNumber() {
    int highestNestNumber = 0;
    ImmutableList<CachedReportWrapper> activeReports = listActiveReportsWithDuplicates();
    for (CachedReportWrapper wrapper : activeReports) {
      highestNestNumber = Math.max(highestNestNumber, wrapper.getReport().getNestNumber());
    }
    return highestNestNumber;
  }

  public int getHighestPossibleFalseCrawlNumber() {
    int highestPFCNumber = 0;
    ImmutableList<CachedReportWrapper> activeReports = listActiveReportsWithDuplicates();
    for (CachedReportWrapper wrapper : activeReports) {
      highestPFCNumber = Math.max(highestPFCNumber, wrapper.getReport().getPossibleFalseCrawlNumber());
    }
    return highestPFCNumber;
  }

  /**
   * Gets the highest false crawl number in any active, non-deleted reports.
   */
  public int getHighestFalseCrawlNumber() {
    int highestFCNumber = 0;
    ImmutableList<CachedReportWrapper> activeReports = listActiveReportsWithDuplicates();
    for (CachedReportWrapper wrapper : activeReports) {
      highestFCNumber = Math.max(highestFCNumber, wrapper.getReport().getFalseCrawlNumber());
    }
    return highestFCNumber;
  }

  private Cursor getActiveReportsCursor(SQLiteDatabase db) {
    return db.query(
        ReportsTable.TABLE_NAME, // table name
        CachedReportWrapper.requiredColumns, // cols to select
        and(isTrue(ReportsTable.COLUMN_ACTIVE), isFalse(ReportsTable.COLUMN_DELETED) ), // where
        null, // don't need selection args
        null, // don't group
        null, // don't filter
        null // sort
        );
  }

  public static class CachedReportWrapper {
    private int localId;
    private Optional<Long> reportId;
    private Optional<Long> version;
    private boolean synched;
    private boolean active;
    private boolean deleted;
    private long lastUpdatedTimestamp;
    private Report report;
    private boolean possible_false_crawl_duplicate;
    private List<String> unsynchedImageFileNames;

    static final String [] requiredColumns = {
      ReportsTable.COLUMN_LOCAL_ID.name,
      ReportsTable.COLUMN_ACTIVE.name,
      ReportsTable.COLUMN_SYNCED.name,
      ReportsTable.COLUMN_DELETED.name,
      ReportsTable.COLUMN_REPORT.name,
      ReportsTable.COLUMN_TS_LOCAL_UPDATE.name,
      ReportsTable.COLUMN_REPORT_ID.name,
      ReportsTable.COLUMN_VERSION.name};

    static CachedReportWrapper from(Cursor cursor) {
      CachedReportWrapper report = new CachedReportWrapper();
      report.localId = Sql.getInt(cursor, ReportsTable.COLUMN_LOCAL_ID);
      report.reportId = Sql.getOptLong(cursor, ReportsTable.COLUMN_REPORT_ID);
      report.version = Sql.getOptLong(cursor, ReportsTable.COLUMN_VERSION);
      report.active = Sql.getBool(cursor, ReportsTable.COLUMN_ACTIVE);
      report.deleted = Sql.getBool(cursor, ReportsTable.COLUMN_DELETED);
      report.synched = Sql.getBool(cursor, ReportsTable.COLUMN_SYNCED);
      report.lastUpdatedTimestamp = Sql.getLong(cursor, ReportsTable.COLUMN_TS_LOCAL_UPDATE);
      report.unsynchedImageFileNames = new ArrayList<String>();

      Optional<Report> proto =
            Sql.getProto(cursor, ReportsTable.COLUMN_REPORT, Report.getDefaultInstance());
      if (proto.isPresent()) {
        report.report = proto.get();
      }
      return report;
    }

    public boolean getPossibleFalseCrawlDuplicate() {
      return possible_false_crawl_duplicate;
    }
    public void setPossibleFalseCrawlDuplicate(boolean isDupe) {
      this.possible_false_crawl_duplicate = isDupe;
    }

    public int getLocalId() {
      return localId;
    }

    public void setLocalId(int localId) {
      this.localId = localId;
    }

    public Optional<Long> getReportId() {
      return reportId;
    }

    public void setReportId(Optional<Long> reportId) {
      this.reportId = reportId;
    }

    public Optional<Long> getVersion() {
      return version;
    }

    public void setVersion(Optional<Long> version) {
      this.version = version;
    }

    public boolean isSynched() {
      return synched;
    }

    public void setSynched(boolean synched) {
      this.synched = synched;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }

    public boolean isDeleted() {
      return deleted;
    }

    public void setDeleted(boolean deleted) {
      this.deleted = deleted;
    }

    public long getLastUpdatedTimestamp() {
      return lastUpdatedTimestamp;
    }

    public void setLastUpdatedTimestamp(long lastUpdatedTimestamp) {
      this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }

    public Report getReport() {
      return report;
    }

    public void setReport(Report report) {
      this.report = report;
    }

    public List<String> getUnsynchedImageFileNames() {
      return unsynchedImageFileNames;
    }

    public void setUnsynchedImageFileNames(List<String> unsynchedImageFileNames) {
      this.unsynchedImageFileNames = unsynchedImageFileNames;
    }
  }

  final static class StorageDefinition {
    static final String DATABASE_NAME = "report_storage.db";
    static final int SCHEMA_VERSION = 1; // MUST INCREMENT if you change anything in this class.
    private StorageDefinition() {}

    static class ReportsTable implements BaseColumns, Sql.Table {
      static final String TABLE_NAME = "reports";
      static final Column COLUMN_LOCAL_ID = new Column("local_id", Type.INTEGER, Column.PRIMARY);
      static final Column COLUMN_REPORT_ID = new Column("report_id", Type.LONG);
      static final Column COLUMN_TS_LOCAL_ADD = new Column("local_add_timestamp", Type.LONG);
      static final Column COLUMN_TS_LOCAL_UPDATE =
          new Column("local_update_timestamp", Type.LONG);
      static final Column COLUMN_VERSION = new Column("version", Type.LONG);
      static final Column COLUMN_ACTIVE = new Column("active", Type.BOOLEAN, "1");
      static final Column COLUMN_DELETED = new Column("deleted", Type.BOOLEAN, "0");
      static final Column COLUMN_SYNCED = new Column("synced", Type.BOOLEAN, "1");
      static final Column COLUMN_REPORT = new Column("report", Type.BLOB);

      static final List<Column> LAYOUT = ImmutableList.of(
          COLUMN_LOCAL_ID,
          COLUMN_REPORT_ID,
          COLUMN_TS_LOCAL_ADD,
          COLUMN_TS_LOCAL_UPDATE,
          COLUMN_VERSION,
          COLUMN_ACTIVE,
          COLUMN_DELETED,
          COLUMN_SYNCED,
          COLUMN_REPORT);

      @Override public String getName() {
        return TABLE_NAME;
      }

      @Override public List<Column> getLayout() {
        return LAYOUT;
      }

      @Override public List<List<Column>> getIndices() {
        return Collections.emptyList();
      }

      static String keyEquals(long value) {
        return whereEquals(ReportsTable.COLUMN_LOCAL_ID, value);
      }
    }

    static class ImagesTable implements BaseColumns, Sql.Table {
      static final String TABLE_NAME = "images";
      static final Column COLUMN_IMAGE_ID = new Column("image_id", Type.INTEGER, Column.PRIMARY);
      static final Column COLUMN_LOCAL_REPORT_ID = new Column("local_report_id", Type.LONG);
      static final Column COLUMN_FILE_NAME = new Column("path", Type.TEXT);
      static final Column COLUMN_TS_LOCAL_ADD = new Column("local_add_timestamp", Type.LONG);
      static final Column COLUMN_TS_LOCAL_UPDATE =
          new Column("local_update_timestamp", Type.LONG);
      static final Column COLUMN_SYNCED = new Column("synced", Type.BOOLEAN, "0");

      static final List<Column> LAYOUT = ImmutableList.of(
          COLUMN_IMAGE_ID,
          COLUMN_LOCAL_REPORT_ID,
          COLUMN_FILE_NAME,
          COLUMN_TS_LOCAL_ADD,
          COLUMN_TS_LOCAL_UPDATE,
          COLUMN_SYNCED);

      // Implement Indices.
      static final List<List<Column>> INDICES = ImmutableList.of(
          Arrays.asList(COLUMN_FILE_NAME, ImagesTable.COLUMN_LOCAL_REPORT_ID));

      @Override public String getName() {
        return TABLE_NAME;
      }

      @Override public List<Column> getLayout() {
        return LAYOUT;
      }

      @Override public List<List<Column>> getIndices() {
        return INDICES;
      }

      static String keyEquals(long value) {
        return whereEquals(ImagesTable.COLUMN_IMAGE_ID, value);
      }
    }

    static class DbHelper extends SQLiteOpenHelper {
      // If you increment this must change onUpgrade to upgrade old versions up.
      static final int SCHEMA_VERSION = 1;
      DbHelper(Context context) {
        super(context, DATABASE_NAME, null, SCHEMA_VERSION);
      }

      @Override
      public void onCreate(SQLiteDatabase db) {
        db.execSQL(Sql.create(new ReportsTable()));
        db.execSQL(Sql.create(new ImagesTable()));
      }

      @Override
      public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Preconditions.checkArgument(newVersion <= SCHEMA_VERSION,
            "Upgrading to invalid version:" + newVersion
            + " we only support up to:" + SCHEMA_VERSION);
        // TODO: First time we implement upgrading, need to support some UI telling user to wait.
      }
    }
  }
}
