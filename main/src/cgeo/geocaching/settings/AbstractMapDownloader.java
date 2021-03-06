package cgeo.geocaching.settings;

import cgeo.geocaching.CgeoApplication;
import cgeo.geocaching.R;
import cgeo.geocaching.models.OfflineMap;
import cgeo.geocaching.utils.MatcherWrapper;

import android.net.Uri;

import androidx.annotation.StringRes;

import java.util.List;
import java.util.regex.Pattern;

public abstract class AbstractMapDownloader {
    public OfflineMap.OfflineMapType offlineMapType;
    public Uri mapBase;
    public String mapSourceName;
    public String mapSourceInfo;
    public String projectUrl;
    public String likeItUrl;
    public static final String oneDirUp = CgeoApplication.getInstance().getString(R.string.downloadmap_onedirup);

    AbstractMapDownloader(final OfflineMap.OfflineMapType offlineMapType, final @StringRes int mapBase, final @StringRes int mapSourceName, final @StringRes int mapSourceInfo, final @StringRes int projectUrl, final @StringRes int likeItUrl) {
        this.offlineMapType = offlineMapType;
        this.mapBase = Uri.parse(CgeoApplication.getInstance().getString(mapBase));
        this.mapSourceName = CgeoApplication.getInstance().getString(mapSourceName);
        this.mapSourceInfo = mapSourceInfo == 0 ? "" : CgeoApplication.getInstance().getString(mapSourceInfo);
        this.projectUrl = projectUrl == 0 ? "" : CgeoApplication.getInstance().getString(projectUrl);
        if (projectUrl != 0) {
            this.mapSourceInfo += (mapSourceInfo != 0 ? "\n" : "") + "(" + this.projectUrl + ")";
        }
        this.likeItUrl = likeItUrl == 0 ? "" : CgeoApplication.getInstance().getString(likeItUrl);
    }

    // find available maps, dir-up, subdirs
    protected abstract void analyzePage(Uri uri, List<OfflineMap> list, String page);

    // find source for single map
    protected abstract OfflineMap findMap(String page, String remoteUrl, String remoteFilename);

    // generic matchers
    protected void basicUpMatcher(final Uri uri, final List<OfflineMap> list, final String page, final Pattern patternUp) {
        if (!mapBase.equals(uri)) {
            final MatcherWrapper matchUp = new MatcherWrapper(patternUp, page);
            if (matchUp.find()) {
                final String oneUp = uri.toString();
                final int endOfPreviousSegment = oneUp.lastIndexOf("/", oneUp.length() - 2); // skip trailing "/"
                if (endOfPreviousSegment > -1) {
                    final OfflineMap offlineMap = new OfflineMap(oneDirUp, Uri.parse(oneUp.substring(0, endOfPreviousSegment + 1)), true, "", "", offlineMapType);
                    list.add(offlineMap);
                }
            }
        }
    }

}
