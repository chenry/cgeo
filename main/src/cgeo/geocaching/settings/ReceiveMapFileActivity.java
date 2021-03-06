package cgeo.geocaching.settings;

import cgeo.geocaching.R;
import cgeo.geocaching.activity.AbstractActivity;
import cgeo.geocaching.maps.mapsforge.MapsforgeMapProvider;
import cgeo.geocaching.models.OfflineMap;
import cgeo.geocaching.storage.ContentStorage;
import cgeo.geocaching.storage.PersistableFolder;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.utils.AsyncTaskWithProgressText;
import cgeo.geocaching.utils.FileNameCreator;
import cgeo.geocaching.utils.FileUtils;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.MapDownloadUtils;
import cgeo.geocaching.utils.OfflineMapUtils;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Receives a map file via intent, moves it to the currently set map directory and sets it as current map source.
 * If no map directory is set currently, default map directory is used, created if needed, and saved as map directory in preferences.
 * If the map file already exists under that name in the map directory, you have the option to either overwrite it or save it under a randomly generated name.
 */
public class ReceiveMapFileActivity extends AbstractActivity {

    public static final String EXTRA_FILENAME = "filename";

    private Uri uri = null;
    private String filename = null;
    private String fileinfo = "";

    private String sourceURL = "";
    private long sourceDate = 0;
    private int offlineMapTypeId = OfflineMap.OfflineMapType.DEFAULT;

    private static final String MAP_EXTENSION = ".map";

    protected enum CopyStates {
        SUCCESS, CANCELLED, IO_EXCEPTION, FILENOTFOUND_EXCEPTION, UNKNOWN_STATE
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme();

        final Intent intent = getIntent();
        uri = intent.getData();
        final String preset = intent.getStringExtra(EXTRA_FILENAME);
        sourceURL = intent.getStringExtra(MapDownloadUtils.RESULT_CHOSEN_URL);
        sourceDate = intent.getLongExtra(MapDownloadUtils.RESULT_DATE, 0);
        offlineMapTypeId = intent.getIntExtra(MapDownloadUtils.RESULT_TYPEID, OfflineMap.OfflineMapType.DEFAULT);

        MapDownloadUtils.checkMapDirectory(this, false, (folder, isWritable) -> {
            if (isWritable) {
                boolean foundMapInZip = false;
                //mapDirectory = Settings.getMapFileDirectory();
                // test if ZIP file received
                try (BufferedInputStream bis = new BufferedInputStream(getContentResolver().openInputStream(uri));
                    ZipInputStream zis = new ZipInputStream(bis)) {
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String filename = ze.getName();
                        final int posExt = filename.lastIndexOf('.');
                        if (posExt != -1 && (MAP_EXTENSION.equals(filename.substring(posExt)))) {
                            final int posInfix = filename.indexOf("_oam.osm.");
                            if (posInfix != -1) {
                                filename = filename.substring(0, posInfix) + filename.substring(posInfix + 8);
                            }
                            // found map file within zip
                            if (guessFilename(filename)) {
                                new CopyTask(this, true, ze.getName()).execute();
                                foundMapInZip = true;
                            }
                        }
                    }
                } catch (IOException e) {
                    // ignore ZIP errors
                }
                // if no ZIP file: continue with copying the file
                if (!foundMapInZip && guessFilename(preset)) {
                    new CopyTask(this, false, null).execute();
                }
            } else {
                finish();
            }
        });
    }

    // try to guess a filename, otherwise chose randomized filename
    private boolean guessFilename(final String preset) {
        filename = StringUtils.isNotBlank(preset) ? preset : uri.getPath();    // uri.getLastPathSegment doesn't help here, if path is encoded
        if (filename != null) {
            filename = FileUtils.getFilenameFromPath(filename);
            final int posExt = filename.lastIndexOf('.');
            if (posExt == -1 || !(MAP_EXTENSION.equals(filename.substring(posExt)))) {
                filename += MAP_EXTENSION;
            }
        }
        if (filename == null) {
            filename = FileNameCreator.OFFLINE_MAPS.createName();
        }
        fileinfo = filename;
        if (fileinfo != null) {
            fileinfo = fileinfo.substring(0, fileinfo.length() - MAP_EXTENSION.length());
        }
        return true;
    }

    protected class CopyTask extends AsyncTaskWithProgressText<String, CopyStates> {
        private long bytesCopied = 0;
        private final String progressFormat = getString(R.string.receivemapfile_kb_copied);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Activity context;
        private final boolean isZipFile;
        private final String nameWithinZip;

        CopyTask(final Activity activity, final boolean isZipFile, final String nameWithinZip) {
            super(activity, activity.getString(R.string.receivemapfile_intenttitle), "");
            setOnCancelListener((dialog, which) -> cancelled.set(true));
            context = activity;
            this.isZipFile = isZipFile;
            this.nameWithinZip = nameWithinZip;
        }

        @Override
        protected CopyStates doInBackgroundInternal(final String[] logTexts) {
            CopyStates status = CopyStates.UNKNOWN_STATE;

            Log.d("start receiving map file: " + filename);
            InputStream inputStream = null;
            final Uri outputUri = ContentStorage.get().create(PersistableFolder.OFFLINE_MAPS, filename);

            try {
                inputStream = new BufferedInputStream(getContentResolver().openInputStream(uri));
                if (isZipFile) {
                    try (ZipInputStream zis = new ZipInputStream(inputStream)) {
                        ZipEntry ze;
                        while ((ze = zis.getNextEntry()) != null) {
                            if (ze.getName().equals(nameWithinZip)) {
                                status = doCopy(zis, outputUri);
                            }
                        }
                    } catch (IOException e) {
                        Log.e("IOException on receiving map file: " + e.getMessage());
                        status = CopyStates.IO_EXCEPTION;
                    }
                } else {
                    status = doCopy(inputStream, outputUri);
                }
            } catch (FileNotFoundException e) {
                return CopyStates.FILENOTFOUND_EXCEPTION;
            } finally {
                IOUtils.closeQuietly(inputStream);
            }

            // clean up and refresh available map list
            if (!cancelled.get()) {
                status = CopyStates.SUCCESS;
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (IllegalArgumentException iae) {
                    Log.w("Deleting Uri '" + uri + "' failed, will be ignored", iae);
                }
                // update offline maps AFTER deleting source file. This handles the very special case when Map Folder = Download Folder
                MapsforgeMapProvider.getInstance().updateOfflineMaps(outputUri);
            } else {
                ContentStorage.get().delete(outputUri);
                status = CopyStates.CANCELLED;
            }

            return status;
        }

        private CopyStates doCopy(final InputStream inputStream, final Uri outputUri) {
            OutputStream outputStream = null;
            try {
                outputStream = ContentStorage.get().openForWrite(outputUri);
                final byte[] buffer = new byte[64 << 10];
                int length = 0;
                while (!cancelled.get() && (length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                    bytesCopied += length;
                    publishProgress(String.format(progressFormat, bytesCopied >> 10));
                }
                return CopyStates.SUCCESS;
            } catch (IOException e) {
                Log.e("IOException on receiving map file: " + e.getMessage());
                return CopyStates.IO_EXCEPTION;
            } finally {
                IOUtils.closeQuietly(inputStream, outputStream);
            }
        }

        @Override
        protected void onPostExecuteInternal(final CopyStates status) {
            final String result;
            switch (status) {
                case SUCCESS:
                    result = String.format(getString(R.string.receivemapfile_success), fileinfo);
                    if (StringUtils.isNotBlank(sourceURL)) {
                        OfflineMapUtils.writeInfo(sourceURL, filename, OfflineMapUtils.getDisplayName(fileinfo), sourceDate, offlineMapTypeId);
                    }
                    break;
                case CANCELLED:
                    result = getString(R.string.receivemapfile_cancelled);
                    break;
                case IO_EXCEPTION:
                    result = String.format(getString(R.string.receivemapfile_error_io_exception), PersistableFolder.OFFLINE_MAPS);
                    break;
                case FILENOTFOUND_EXCEPTION:
                    result = getString(R.string.receivemapfile_error_filenotfound_exception);
                    break;
                default:
                    result = getString(R.string.receivemapfile_error);
                    break;
            }
            Dialogs.message(context, getString(R.string.receivemapfile_intenttitle), result, getString(android.R.string.ok), (dialog, button) -> finish());
        }

    }

}
