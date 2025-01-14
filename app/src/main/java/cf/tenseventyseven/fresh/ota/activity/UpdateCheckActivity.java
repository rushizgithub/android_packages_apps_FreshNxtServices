package cf.tenseventyseven.fresh.ota.activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;

import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2core.DownloadBlock;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.dlyt.yanndroid.oneui.dialog.AlertDialog;
import de.dlyt.yanndroid.oneui.layout.ToolbarLayout;
import de.dlyt.yanndroid.oneui.view.Toast;
import cf.tenseventyseven.fresh.R;
import cf.tenseventyseven.fresh.ota.SoftwareUpdate;
import cf.tenseventyseven.fresh.ota.UpdateNotifications;
import cf.tenseventyseven.fresh.ota.api.UpdateCheck;
import cf.tenseventyseven.fresh.ota.api.UpdateCheckJobService;
import cf.tenseventyseven.fresh.ota.db.CurrentSoftwareUpdate;

public class UpdateCheckActivity extends AppCompatActivity {
    @BindView(R.id.fresh_ota_check_toolbar_layout)
    ToolbarLayout toolbarLayout;

    private static final int PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;

    private Context mContext;
    private Fetch mFetch;
    private final FetchListener mFetchListener = new FetchListener() {
        @Override
        public void onWaitingNetwork(@NonNull Download download) {
        }

        @Override
        public void onStarted(@NonNull Download download, @NonNull List<? extends DownloadBlock> list, int i) {
        }

        @Override
        public void onResumed(@NonNull Download download) {
        }

        @Override
        public void onRemoved(@NonNull Download download) {
        }

        @Override
        public void onQueued(@NonNull Download download, boolean b) {
        }

        @Override
        public void onProgress(@NonNull Download download, long eta, long speed) {
        }

        @Override
        public void onPaused(@NonNull Download download) {
        }

        @Override
        public void onError(@NonNull Download download, @NonNull Error error, @Nullable Throwable throwable) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                showErrorToast(mContext, false);

                // Re-schedule
                UpdateCheckJobService.setupCheckJob(mContext);
                handler.postDelayed(UpdateCheckActivity.this::finish, 1000);
            });
        }

        @Override
        public void onDownloadBlockUpdated(@NonNull Download download, @NonNull DownloadBlock downloadBlock, int i) {
        }

        @Override
        public void onDeleted(@NonNull Download download) {
        }

        @Override
        public void onCompleted(@NonNull Download download) {
            Handler handler = new Handler(Looper.getMainLooper());
            final File json = new File(mContext.getFilesDir(), UpdateCheck.MANIFEST_FILE_NAME);

            try {
                if (!json.exists() || !UpdateCheck.parseManifest(mContext, json)) {
                    handler.postDelayed(() -> {
                        showErrorToast(mContext, false);
                        handler.postDelayed(UpdateCheckActivity.this::finish, 1000);
                    }, 2000);
                    return;
                }

                UpdateCheckJobService.setupCheckJob(mContext);

                handler.postDelayed(() -> {
                    Intent intent = new Intent(mContext,
                            UpdateCheck.getUpdateAvailability(mContext) ? UpdateAvailableActivity.class : DeviceUpdatedActivity.class);
                    startActivity(intent);
                    UpdateCheckActivity.this.finish();
                }, 2000);
            } catch (IOException | JSONException e) {
                handler.post(() -> {
                    showErrorToast(mContext, false);

                    // Re-schedule
                    UpdateCheckJobService.setupCheckJob(mContext);
                    handler.postDelayed(UpdateCheckActivity.this::finish, 1000);
                });
            }
        }

        @Override
        public void onCancelled(@NonNull Download download) {
        }

        @Override
        public void onAdded(@NonNull Download download) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fresh_update_check_activity);
        ButterKnife.bind(this);

        mContext = this;

        toolbarLayout.setExpanded(false);
        toolbarLayout.setNavigationButtonTooltip(getString(R.string.sesl_navigate_up));
        toolbarLayout.setNavigationButtonOnClickListener(v -> onBackPressed());
        setSupportActionBar(toolbarLayout.getToolbar());

        // But check permissions first - download will be started in the callback
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
        }

        UpdateNotifications.setupNotificationChannels(this);

        // Check for permissions before initiating update task.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            checkForUpdates(this);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        // Re-schedule updates check.
        UpdateCheckJobService.setupCheckJob(this);
        UpdateCheckActivity.this.finish();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mFetch != null)
            mFetch.removeListener(mFetchListener);

        UpdateCheck.tryStopService(this);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE && (grantResults.length <= 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showPermissionDialog();
        }
    }

    private void checkForUpdates(Context context) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        UpdateCheckJobService.cancelCheckJob(context);

        UpdateCheck.startService(this);
        mFetch = UpdateCheck.getFetchInstance(context);
        mFetch.addListener(mFetchListener);

        executor.execute(() -> {
            String server = UpdateCheck.whichServiceReachable(context);

            if (server == null) {
                handler.post(() -> {
                    showErrorToast(context, true);

                    // Re-schedule
                    UpdateCheckJobService.setupCheckJob(context);
                    handler.postDelayed(UpdateCheckActivity.this::finish, 1000);
                });
                return;
            }

            int currentState = CurrentSoftwareUpdate.getOtaState(context);

            if (currentState == SoftwareUpdate.OTA_INSTALL_STATE_DOWNLOADED) {
                UpdateNotifications.showPreUpdateNotification(context);
                UpdateCheckJobService.setupCheckJob(context);

                handler.post(() -> {
                    Intent intent = new Intent(context, UpdateAvailableActivity.class);
                    startActivity(intent);
                    UpdateCheckActivity.this.finish();
                });

                return;
            }

            // Show update dialog if update is currently being downloaded
            if (UpdateCheck.getUpdateAvailability(context) && currentState != SoftwareUpdate.OTA_INSTALL_STATE_NOT_STARTED) {
                if (currentState != SoftwareUpdate.OTA_INSTALL_STATE_DOWNLOADING)
                    UpdateNotifications.showNewUpdateNotification(context);

                UpdateCheckJobService.setupCheckJob(context);

                handler.post(() -> {
                    Intent intent = new Intent(context, UpdateAvailableActivity.class);
                    startActivity(intent);
                    UpdateCheckActivity.this.finish();
                });

                return;
            }

            UpdateCheck.downloadManifest(context, error -> {
                handler.post(() -> {
                    showErrorToast(mContext, false);

                    // Re-schedule
                    UpdateCheckJobService.setupCheckJob(mContext);
                    handler.postDelayed(UpdateCheckActivity.this::finish, 1000);
                });
            });

        });
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.OneUITheme))
                .setTitle(R.string.fresh_permissions_storage_title)
                .setMessage(R.string.fresh_permissions_storage_description)
                .setPositiveButton(R.string.qs_dialog_ok, (dialog, which) -> UpdateCheckActivity.this.finish())
                .create()
                .show();
    }

    private static void showErrorToast(Context context, boolean noConnection) {
        Toast.makeText(context, noConnection ? R.string.fresh_ota_toast_check_failed_offline : R.string.fresh_ota_toast_check_failed_generic, Toast.LENGTH_SHORT).show();
        UpdateNotifications.cancelOngoingCheckNotification(context);
    }
}