package dev.pdmv.os;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Gives the system installer read access to exactly the verified update. */
public final class UpdateApkProvider extends ContentProvider {
    private static final String NAME = "pdmvOS-update.apk";
    static final String MIME_TYPE = "application/vnd.android.package-archive";

    static File directory(Context context) {
        return new File(context.getCacheDir(), "launcher-update");
    }

    static File apk(Context context, String hash) {
        return new File(directory(context), hash + ".apk");
    }

    static Uri uri(Context context, String hash) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".update")
            .appendPath(hash).build();
    }

    private File verifiedFile(Uri uri) {
        Context context = getContext();
        String hash = uri.getLastPathSegment();
        return hash != null && hash.matches("[a-f0-9]{64}") && uri(context, hash).equals(uri)
            ? apk(context, hash) : null;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        return verifiedFile(uri) == null ? null : MIME_TYPE;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = verifiedFile(uri);
        if (file == null || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = verifiedFile(uri);
        if (file == null || !file.isFile()) return null;
        String[] columns = projection == null
            ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? NAME
                : OpenableColumns.SIZE.equals(column) ? file.length() : null);
        }
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
