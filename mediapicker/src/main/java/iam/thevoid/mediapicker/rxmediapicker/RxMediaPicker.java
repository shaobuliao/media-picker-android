package iam.thevoid.mediapicker.rxmediapicker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.tbruyelle.rxpermissions.RxPermissions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import iam.thevoid.mediapicker.builder.VideoIntentBuilder;
import iam.thevoid.mediapicker.cropper.CropArea;
import iam.thevoid.mediapicker.chooser.IntentData;
import iam.thevoid.mediapicker.chooser.MediaPickSelectAppDialog;
import iam.thevoid.mediapicker.rxmediapicker.metrics.Duration;
import iam.thevoid.mediapicker.rxmediapicker.metrics.MemorySize;
import iam.thevoid.mediapicker.rxmediapicker.metrics.Quality;
import iam.thevoid.mediapicker.rxmediapicker.metrics.Resolution;
import iam.thevoid.mediapicker.util.ConcurrencyUtil;
import iam.thevoid.mediapicker.util.IntentUtils;
import iam.thevoid.mediapicker.rxmediapicker.metrics.SizeUnit;
import rx.Observable;
import rx.subjects.PublishSubject;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;


public class RxMediaPicker implements MediaPickSelectAppDialog.OnSelectAppCallback {

    static final String EXTRA_CROP_AREA = "EXTRA_CROP_AREA";
    static final String EXTRA_INTENT = "EXTRA_INTENT";
    static final String EXTRA_URI = "EXTRA_URI";
    static final String EXTRA_REQUEST_CODE = "EXTRA_REQUEST_CODE";
    static final String EXTRA_PHOTO_MAX_SIZE = "EXTRA_PHOTO_MAX_SIZE";
    static final String EXTRA_VIDEO_MAX_SIZE = "EXTRA_VIDEO_MAX_SIZE";
    static final String EXTRA_PHOTO_MAX_PIXEL_WIDTH = "EXTRA_PHOTO_MAX_PIXEL_WIDTH";
    static final String EXTRA_PHOTO_MAX_PIXEL_HEIGHT = "EXTRA_PHOTO_MAX_PIXEL_HEIGHT";
    static final String EXTRA_VIDEO_MAX_DURATION = "EXTRA_VIDEO_MAX_DURATION";
    static final String EXTRA_VIDEO_QUALITY = "EXTRA_VIDEO_QUALITY";

    private Context mContext;

    private PublishSubject<Uri> publishSubject;

    private ArrayList<Purpose> purposes = new ArrayList<>(Collections.singletonList(Purpose.Pick.IMAGE));

    private CropArea cropArea;

    private OnDismissListener onDismissListener;

    // Bytes
    private long photoMaxSize;

    // Bytes
    private long videoMaxSize;

    // Pixels
    private long photoMaxPixelsWidth;

    // Pixels
    private long photoMaxPixelsHeight;

    // Seconds
    private long videoMaxDuration;

    // Int
    private long videoQuality;

    private static RxMediaPicker instance;

    private RxMediaPicker() {
    }

    static RxMediaPicker instance() {
        return instance;
    }

    public Observable<Uri> request() {
        Observable.just(null)
                .compose(new RxPermissions(IntentUtils.getActivity(mContext))
                        .ensure(needsPermissions()))
                .filter(aBoolean -> aBoolean)
                .subscribe(aBoolean -> ConcurrencyUtil.postDelayed(this::startSelection, 1), Throwable::printStackTrace);
        publishSubject = PublishSubject.create();
        return publishSubject;
    }

    private void startSelection() {

        if (onlyOneAppCanHandleRequest()) {
            Purpose purpose = purposes.get(0);
            startImagePick(purpose.getIntent(mContext, getBundle()), purpose.requestCode());
            return;
        }

        MediaPickSelectAppDialog.showForResult(mContext, getIntentDatas(), this);
    }

    private ArrayList<IntentData> getIntentDatas() {

        ArrayList<IntentData> intentData = new ArrayList<>();

        for (Purpose purpose : purposes) {
            intentData.add(purpose.getIntentData(mContext, getBundle()));
        }

        return intentData;
    }

    void onImagePicked(Uri uri, boolean completed) {
        if (completed || cropArea == null) {
            if (publishSubject != null) {
                publishSubject.onNext(uri);
                publishSubject.onCompleted();
                mContext = null;
            }
        } else {
            startCrop(uri);
        }
    }

    private void startCrop(Uri uri) {
        IntentUtils.getActivity(mContext)
                .getFragmentManager()
                .beginTransaction()
                .add(HiddenCropFragment.getFragment(cropArea, uri), HiddenCropFragment.class.getCanonicalName())
                .commitAllowingStateLoss();
    }

    private void startImagePick(Intent intent, int requestCode) {
        IntentUtils.getActivity(mContext).getFragmentManager()
                .beginTransaction()
                .add(HiddenPickerFragment.getFragment(intent, cropArea, requestCode), HiddenPickerFragment.class.getCanonicalName())
                .commitAllowingStateLoss();
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    private String[] needsPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            permissions.add(READ_EXTERNAL_STORAGE);
        }
        permissions.add(WRITE_EXTERNAL_STORAGE);
        if (purposes.contains(Purpose.Take.PHOTO) || purposes.contains(Purpose.Take.VIDEO)) {
            permissions.add(CAMERA);
        }

        return permissions.toArray(new String[permissions.size()]);
    }

    @Override
    public void onAppSelect(IntentData intentData) {
        startImagePick(intentData.getIntent(), intentData.getRequestCode());
    }

    private boolean onlyOneAppCanHandleRequest() {
        List<ResolveInfo> resolveInfos = new ArrayList<>();

        for (Purpose purpose : purposes) {
            resolveInfos.addAll(IntentUtils.getResolveInfoList(IntentUtils.getActivity(mContext).getPackageManager(),
                    purpose.getIntent(mContext, getBundle())));
        }

        return resolveInfos.size() == 1;
    }

    private Bundle getBundle() {
        Bundle bundle = new Bundle();
        bundle.putLong(EXTRA_PHOTO_MAX_PIXEL_HEIGHT, photoMaxPixelsHeight);
        bundle.putLong(EXTRA_PHOTO_MAX_PIXEL_WIDTH, photoMaxPixelsWidth);
        bundle.putLong(EXTRA_PHOTO_MAX_SIZE, photoMaxSize);
        bundle.putLong(EXTRA_VIDEO_MAX_DURATION, videoMaxDuration);
        bundle.putLong(EXTRA_VIDEO_MAX_SIZE, videoMaxSize);
        bundle.putLong(EXTRA_VIDEO_QUALITY, videoQuality);
        return bundle;
    }

    public void dismiss() {
        if (onDismissListener != null) {
            onDismissListener.onDismiss();
        }
    }

    public static class Builder {

        private Context mContext;

        private Set<Purpose> purposes = new HashSet<>();

        private CropArea cropArea;

        private OnDismissListener onDismissListener;

        private Duration videoMaxDuration = null;

        private Resolution photoMaxResolution = null;

        private MemorySize photoMaxSize = null;

        private MemorySize videoMaxSize = null;

        private Quality videoQuality = null;

        private Builder(Context context) {
            this.mContext = context;
        }

        public Builder pick(Purpose.Pick... purpose) {
            if (Purpose.contains(Arrays.asList(purpose), Purpose.Pick.IMAGE) &&
                    Purpose.contains(Arrays.asList(purpose), Purpose.Pick.VIDEO)) {
                this.purposes.add(Purpose.Hidden.GALLERY);
                return this;
            }

            this.purposes.addAll(Arrays.asList(purpose));
            return this;
        }

        public Builder take(Purpose.Take... purpose) {
            this.purposes.addAll(Arrays.asList(purpose));
            return this;
        }

        public Builder crop(CropArea cropArea) {
            this.cropArea = cropArea;
            return this;
        }

        public Builder dismiss(OnDismissListener onDismissListener) {
            this.onDismissListener = onDismissListener;
            return this;
        }

        public Builder videoMaxDuration(Duration videoMaxDuration) {
            this.videoMaxDuration = videoMaxDuration;
            return this;
        }

        public Builder photoMaxSize(MemorySize photoMaxSize) {
            this.photoMaxSize = photoMaxSize;
            return this;
        }

        public Builder videoMaxSize(MemorySize videoMaxSize) {
            this.videoMaxSize = videoMaxSize;
            return this;
        }

        public Builder photoMaxResolution(Resolution photoMaxResolution) {
            this.photoMaxResolution = photoMaxResolution;
            return this;
        }

        public Builder videoQuality(Quality videoQuality) {
            this.videoQuality = videoQuality;

            return this;
        }

        public Observable<Uri> build() {

            if (RxMediaPicker.instance == null) {
                RxMediaPicker.instance = new RxMediaPicker();
            }

            RxMediaPicker.instance.purposes = new ArrayList<>(purposes);
            RxMediaPicker.instance.cropArea = cropArea;
            RxMediaPicker.instance.onDismissListener = onDismissListener;
            RxMediaPicker.instance.photoMaxSize = photoMaxSize == null ? -1 : photoMaxSize.getBytes();
            RxMediaPicker.instance.photoMaxPixelsHeight = photoMaxResolution == null ? -1 : photoMaxResolution.getHeight();
            RxMediaPicker.instance.photoMaxPixelsWidth = photoMaxResolution == null ? -1 : photoMaxResolution.getWidth();
            RxMediaPicker.instance.videoMaxDuration = videoMaxDuration == null ? -1 : videoMaxDuration.getSeconds();
            RxMediaPicker.instance.videoMaxSize = videoMaxSize == null ? -1 : videoMaxSize.getBytes();
            RxMediaPicker.instance.videoQuality = videoQuality == null ? -1 : videoQuality.getQuality();
            RxMediaPicker.instance.mContext = mContext;
            mContext = null;
            return RxMediaPicker.instance.request();
        }
    }

    public interface OnDismissListener {
        void onDismiss();
    }
}