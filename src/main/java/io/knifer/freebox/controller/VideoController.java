package io.knifer.freebox.controller;

import cn.hutool.core.collection.CollUtil;
import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.knifer.freebox.component.node.player.BasePlayer;
import io.knifer.freebox.component.node.player.VLCPlayer;
import io.knifer.freebox.constant.BaseValues;
import io.knifer.freebox.constant.CacheKeys;
import io.knifer.freebox.constant.I18nKeys;
import io.knifer.freebox.context.Context;
import io.knifer.freebox.handler.M3u8AdFilterHandler;
import io.knifer.freebox.handler.M3u8TsProxyHandler;
import io.knifer.freebox.handler.impl.BadM3u8TsProxyHandler;
import io.knifer.freebox.handler.impl.SmartM3u8AdFilterHandler;
import io.knifer.freebox.helper.*;
import io.knifer.freebox.model.bo.TVPlayBO;
import io.knifer.freebox.model.bo.VideoDetailsBO;
import io.knifer.freebox.model.bo.VideoPlayInfoBO;
import io.knifer.freebox.model.common.tvbox.Movie;
import io.knifer.freebox.model.common.tvbox.SourceBean;
import io.knifer.freebox.model.common.tvbox.VodInfo;
import io.knifer.freebox.model.domain.M3u8AdFilterResult;
import io.knifer.freebox.model.s2c.DeleteMovieCollectionDTO;
import io.knifer.freebox.model.s2c.GetMovieCollectedStatusDTO;
import io.knifer.freebox.model.s2c.GetPlayerContentDTO;
import io.knifer.freebox.model.s2c.SaveMovieCollectionDTO;
import io.knifer.freebox.spider.template.SpiderTemplate;
import io.knifer.freebox.util.AsyncUtil;
import io.knifer.freebox.util.CollectionUtil;
import io.knifer.freebox.util.HttpUtil;
import io.knifer.freebox.util.json.GsonUtil;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.annotation.Nullable;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 影视详情控制器
 *
 * @author Knifer
 */
@Slf4j
public class VideoController extends BaseController implements Destroyable {

    @FXML
    private Button openInPotPlayerBtn;

    private String currentPlayUrl;
    private Map<String, String> currentPlayHeaders;
    private String currentVideoTitle;
    
    // 新增：用于存储当前最新的调试信息（供右键复制使用）
    private String currentDebugInfo;

    @FXML
    private HBox root;
    @FXML
    private SplitPane videoDetailSplitPane;
    @FXML
    private Label movieTitleLabel;
    @FXML
    private TextFlow movieDetailsTextFlow;
    @FXML
    private TabPane resourceTabPane;
    @FXML
    private Button collectBtn;

    private final FontIcon COLLECT_FONT_ICON = FontIcon.of(FontAwesome.STAR_O, 16, Color.ORANGE);
    private final FontIcon COLLECTED_FONT_ICON = FontIcon.of(FontAwesome.STAR, 16, Color.ORANGE);

    private Movie videoDetail;
    private VideoPlayInfoBO playInfo;
    private SourceBean source;
    private BasePlayer<?> player;
    private SpiderTemplate template;
    private Consumer<VideoPlayInfoBO> onClose;

    private M3u8AdFilterHandler m3u8AdFilterHandler;
    private M3u8TsProxyHandler m3u8TsProxyHandler;

    private Button selectedEpBtn = null;
    private Movie.Video playingVideo;
    private Movie.Video.UrlBean.UrlInfo playingUrlInfo;
    private Movie.Video.UrlBean.UrlInfo.InfoBean playingInfoBean;
    public final BooleanProperty operationLoading = new SimpleBooleanProperty(true);

    private static final Set<String> HTTP_HEADERS_PROXY_EXCLUDE = Set.of(
            "content-length",
            "Content-Length",
            "transfer-encoding",
            "Transfer-Encoding"
    );

    @FXML
    private void initialize() {
        m3u8AdFilterHandler = new SmartM3u8AdFilterHandler();
        m3u8TsProxyHandler = new BadM3u8TsProxyHandler();
        Platform.runLater(() -> {
            VideoDetailsBO bo = getData();

            if (bo == null) {
                ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);

                return;
            }
            videoDetail = bo.getVideoDetail().getMovie();
            playInfo = bo.getPlayInfo();
            source = bo.getSource();
            player = bo.getPlayer();
            template = bo.getTemplate();
            onClose = bo.getOnClose();
            if (videoDetail == null || videoDetail.getVideoList().isEmpty()) {
                ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);
                return;
            }
            // 收藏按钮
            collectBtn.disableProperty().bind(operationLoading);
            template.getMovieCollectedStatus(
                    GetMovieCollectedStatusDTO.of(source.getKey(), videoDetail.getVideoList().get(0).getId()),
                    this::setCollectBtnByCollectedStatus
            );
            // 绑定播放下一集事件
            player.setOnStepForward(this::onPlayerStepForward);
            WindowHelper.getStage(root).setOnCloseRequest(evt -> destroy());
            videoDetailSplitPane.minHeightProperty().bind(root.heightProperty());
            putMovieDataInView();
            startPlayVideo();
        });
    }

    /**
     * 播放下一集
     */
    private void onPlayerStepForward() {
        Iterator<Movie.Video.UrlBean.UrlInfo.InfoBean> beanIter;
        Movie.Video.UrlBean.UrlInfo.InfoBean bean;
        ObservableList<Node> epBtnList;
        Iterator<Node> epBtnIter;
        Button epBtn;

        if (playingVideo == null) {
            return;
        }
        beanIter = playingUrlInfo.getBeanList().iterator();
        while (beanIter.hasNext()) {
            bean = beanIter.next();
            if (bean.getUrl().equals(playingInfoBean.getUrl())) {
                if (beanIter.hasNext()) {
                    // 准备播放下一集，先更新“被选中的当前集按钮”样式
                    epBtnList = ((FlowPane) (
                            (ScrollPane) resourceTabPane.getSelectionModel().getSelectedItem().getContent()
                    ).getContent()).getChildren();
                    epBtnIter = epBtnList.iterator();
                    while (epBtnIter.hasNext()) {
                        epBtn = (Button) epBtnIter.next();
                        if (epBtn == selectedEpBtn) {
                            updateSelectedEpBtn(((Button) epBtnIter.next()));
                            break;
                        }
                    }
                    // 播放下一集，同时更新播放信息
                    playVideo(playingVideo, playingUrlInfo, beanIter.next(), null);
                } else {
                    Platform.runLater(() ->
                            player.showToast(I18nHelper.get(I18nKeys.VIDEO_INFO_NO_MORE_EP))
                    );
                }
                break;
            }
        }
    }

    private void putMovieDataInView() {
        Movie.Video video = videoDetail.getVideoList().get(0);
        ObservableList<Node> detailsPropList = movieDetailsTextFlow.getChildren();
        int year = video.getYear();
        List<Movie.Video.UrlBean.UrlInfo> urlInfoList = video.getUrlBean().getInfoList();
        boolean hasPlayInfo = playInfo != null;
        String playFlag;
        List<Tab> tabs;

        // 影片信息
        movieTitleLabel.setText(video.getName());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_SOURCE, source.getName());
        if (year != 0) {
            addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_YEAR, String.valueOf(year));
        }
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_AREA, video.getArea());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_TYPE, video.getType());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_DIRECTORS, video.getDirector());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_ACTORS, video.getActor());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_LINK, video.getId());
        addMovieDetailsIfExists(detailsPropList, I18nKeys.VIDEO_MOVIE_DETAILS_INTRO, video.getDes());
        // 选集信息
        if (urlInfoList.isEmpty()) {
            ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_RESOURCE);
            return;
        }
        tabs = resourceTabPane.getTabs();
        if (hasPlayInfo) {
            setPlayIndexIfNeeded(playInfo, videoDetail);
            playFlag = ObjectUtils.defaultIfNull(playInfo.getPlayFlag(), StringUtils.EMPTY);
        } else {
            playFlag = StringUtils.EMPTY;
        }
        urlInfoList.forEach(urlInfo -> {
            String urlFlag = urlInfo.getFlag();
            List<Movie.Video.UrlBean.UrlInfo.InfoBean> beanList = urlInfo.getBeanList();
            Tab tab = new Tab(urlFlag);
            FlowPane flowPane = new FlowPane();
            ObservableList<Node> children = flowPane.getChildren();
            ScrollPane scrollPane = new ScrollPane(flowPane);
            CheckMenuItem reverseMenuItem;

            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);
            flowPane.setHgap(10);
            flowPane.setVgap(10);
            flowPane.setAlignment(Pos.TOP_CENTER);
            flowPane.setPadding(new Insets(10, 0, 60, 0));
            if (hasPlayInfo && urlFlag.equals(playInfo.getPlayFlag()) && playInfo.isReverseSort()) {
                Collections.reverse(beanList);
            }
            beanList.forEach(bean -> {
                Button btn = new Button(bean.getName());

                // --- 初始化 Tooltip (包裹在 try-catch 中以防阻断渲染) ---
                try {
                    String rawUrl = bean.getUrl();

                    // 清洗 URL
                    if (rawUrl != null) {
                        rawUrl = rawUrl.trim();
                        if (rawUrl.startsWith("`")) rawUrl = rawUrl.substring(1);
                        if (rawUrl.endsWith("`")) rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
                        rawUrl = rawUrl.trim();
                    }

                    // 使用 updateInfo 设置初始状态 (只显示视频信息)
                    updateInfo(btn, rawUrl, null, null, null, null, null);
                } catch (Exception e) {
                    log.warn("init tooltip failed", e);
                }
                // ----------------------------------------------------

                children.add(btn);
                btn.setOnAction(evt -> {
                    if (btn == selectedEpBtn) {
                        return;
                    }
                    // 选集按钮被点击，更新样式，并播放对应选集集视频
                    updateSelectedEpBtn(btn);
                    playVideo(video, urlInfo, bean, null);
                });
            });
            tab.setContent(scrollPane);
            // 给选集标签页绑定右键菜单
            reverseMenuItem = new CheckMenuItem(I18nHelper.get(I18nKeys.VIDEO_REVERSE));
            reverseMenuItem.setOnAction(evt -> {
                /* 倒序操作 */
                // 数据列表倒叙
                Collections.reverse(beanList);
                // 按钮列表倒序
                FXCollections.reverse(children);
            });
            tab.setContextMenu(new ContextMenu(reverseMenuItem));
            if (hasPlayInfo && playFlag.equals(urlFlag)) {
                // 存在历史记录，且历史记录的选集标签页与当前播放的选集标签页相同，因此要赋予当前选集标签页历史记录中的相关属性
                reverseMenuItem.setSelected(playInfo.isReverseSort());
            }
            // 添加标签页
            tabs.add(tab);
        });
    }

    private void updateSelectedEpBtn(Button newSelectedEpBtn) {
        if (selectedEpBtn != null) {
            selectedEpBtn.getStyleClass().remove("video-details-ep-btn-selected");
        }
        selectedEpBtn = newSelectedEpBtn;
        selectedEpBtn.getStyleClass().add("video-details-ep-btn-selected");
    }

    private void addMovieDetailsIfExists(
            ObservableList<Node> children,
            String propNameI18nKey,
            String propValue
    ) {
        Text propValueText;
        Text propNameText;
        Tooltip tooltip;

        if (StringUtils.isBlank(propValue)) {
            return;
        }
        propValue = StringUtils.trim(propValue);
        if (!children.isEmpty()) {
            children.add(new Text("\n"));
        }
        propNameText = new Text(I18nHelper.get(propNameI18nKey));
        propNameText.getStyleClass().add("video-details-prop-name");
        if (propValue.length() > 50) {
            propValueText = new Text(propValue.substring(0, 30) + ".....");
            tooltip = new Tooltip(propValue);
            tooltip.setPrefWidth(250);
            tooltip.setWrapText(true);
            Tooltip.install(propValueText, tooltip);
        } else {
            propValueText = new Text(propValue);
        }
        children.add(propNameText);
        children.add(propValueText);
        movieDetailsTextFlow.setMinHeight(
                movieDetailsTextFlow.getHeight() + propValueText.getFont().getSize()
        );
    }

    /**
     * 如有必要，设置要恢复播放的集数索引
     * 在与FongMi TV配对时，由于其提供的历史记录中没有playIndex，因此需要通过playNote来匹配
     * @param playInfo 播放信息
     * @param movie 影视数据
     */
    private void setPlayIndexIfNeeded(VideoPlayInfoBO playInfo, Movie movie) {
        int playIndex = playInfo.getPlayIndex();
        List<Movie.Video> videos;
        Movie.Video.UrlBean urlBean;
        List<Movie.Video.UrlBean.UrlInfo> infoList;
        int infoListSize;
        Movie.Video.UrlBean.UrlInfo urlInfo;
        String playFlag;
        final String finalPlayFlag;
        String playNote;

        if (playIndex > 0) {

            return;
        }
        videos = movie.getVideoList();
        urlBean = videos.get(0).getUrlBean();
        infoList = urlBean.getInfoList();
        infoListSize = CollUtil.size(infoList);
        if (infoListSize == 0) {

            return;
        }
        playFlag = playInfo.getPlayFlag();
        if (playFlag == null || infoListSize == 1) {
            playFlag = infoList.get(0).getFlag();
        }
        playNote = playInfo.getPlayNote();
        if (playNote == null) {

            return;
        }
        finalPlayFlag = playFlag;
        urlInfo = CollectionUtil.findFirst(infoList, info -> finalPlayFlag.equals(info.getFlag()))
                .orElse(null);
        if (urlInfo == null) {

            return;
        }
        playIndex = CollUtil.indexOf(urlInfo.getBeanList(), urlB -> playNote.equals(urlB.getName()));
        if (playIndex != -1) {
            playInfo.setPlayIndex(playIndex);
        }
    }

    private void startPlayVideo() {
        Movie.Video video = videoDetail.getVideoList().get(0);
        Movie.Video.UrlBean.UrlInfo urlInfo;
        List<Movie.Video.UrlBean.UrlInfo.InfoBean> beanList;
        Movie.Video.UrlBean.UrlInfo.InfoBean infoBean;
        String playFlag;
        int playIndex;
        int finalPlayIndex;
        Long progress = null;

        if (playInfo == null) {
            // 没有附带播放信息，直接播放第一个视频
            urlInfo = video.getUrlBean().getInfoList().get(0);
            infoBean = urlInfo.getBeanList().get(0);
            // 设置第一个tab内的第一个按钮为选中状态
            selectedEpBtn = (
                    (Button) ((FlowPane) ((ScrollPane) resourceTabPane.getTabs().get(0).getContent()).getContent())
                            .getChildren()
                            .get(0)
            );
        } else {
            playFlag = ObjectUtils.defaultIfNull(playInfo.getPlayFlag(), StringUtils.EMPTY);
            urlInfo = CollectionUtil.findFirst(
                    video.getUrlBean().getInfoList(), info -> playFlag.equals(info.getFlag())
            ).orElseGet(() -> video.getUrlBean().getInfoList().get(0));
            playIndex = playInfo.getPlayIndex();
            beanList = urlInfo.getBeanList();
            if (playIndex < 0 || beanList.size() - 1 < playIndex) {
                playIndex = 0;
            }
            infoBean = beanList.get(playIndex);
            finalPlayIndex = playIndex;
            // 设置指定选集按钮为选中状态
            CollectionUtil.findFirst(resourceTabPane.getTabs(), t -> t.getText().equals(playFlag))
                    .ifPresentOrElse(tab -> selectedEpBtn = (
                            (Button) ((FlowPane) ((ScrollPane) tab.getContent()).getContent())
                                    .getChildren()
                                    .get(finalPlayIndex)
                    ), () -> selectedEpBtn = (
                            (Button) ((FlowPane) ((ScrollPane) resourceTabPane.getTabs().get(0).getContent())
                                    .getContent())
                                    .getChildren()
                                    .get(0)
                    ));
            progress = playInfo.getProgress();
        }
        selectedEpBtn.getStyleClass().add("video-details-ep-btn-selected");
        playVideo(video, urlInfo, infoBean, progress);
    }

    /**
     * 播放视频
     * @param video 影视信息
     * @param urlInfo 播放源信息
     * @param urlInfoBean 播放集数
     * @param progress 播放进度（为空则从0播放）
     */
    private void playVideo(
            Movie.Video video,
            Movie.Video.UrlBean.UrlInfo urlInfo,
            Movie.Video.UrlBean.UrlInfo.InfoBean urlInfoBean,
            @Nullable Long progress
    ) {
        String flag = urlInfo.getFlag();

        Platform.runLater(() -> player.stop());
        template.getPlayerContent(
                GetPlayerContentDTO.of(video.getSourceKey(), flag, urlInfoBean.getUrl()),
                playerContentJson ->
                    Platform.runLater(() -> {
                        JsonElement propsElm;
                        JsonObject propsObj;
                        JsonElement elm;
                        String playUrl;
                        int parse;
                        int jx;
                        Map<String, String> headers;
                        String videoTitle;

                        if (playerContentJson == null) {
                            ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);
                            return;
                        }
                        propsElm = playerContentJson.get("nameValuePairs");
                        if (propsElm == null) {
                            ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);
                            return;
                        }
                        propsObj = propsElm.getAsJsonObject();
                        elm = propsObj.get("url");
                        if (elm == null) {
                            ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);
                            return;
                        }
                        playUrl = elm.getAsString();
                        if (StringUtils.isBlank(playUrl)) {
                            ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_NO_DATA);
                            return;
                        }
                        // 移除解码逻辑，保持 URL 编码状态，避免中文路径导致播放器无法识别
                        // playUrl = URLDecoder.decode(playUrl, Charsets.UTF_8);
                        
                        // --- 关键修复：在源头清洗 URL，确保播放和显示都使用干净的链接 ---
                        if (playUrl != null) {
                            playUrl = playUrl.trim();
                            if (playUrl.startsWith("`")) playUrl = playUrl.substring(1);
                            if (playUrl.endsWith("`")) playUrl = playUrl.substring(0, playUrl.length() - 1);
                            playUrl = playUrl.trim();
                            
                            // 同时更新 JSON 对象里的 URL，确保 Tooltip 显示的 JSON 也是干净的
                            if (propsObj != null && propsObj.has("url")) {
                                propsObj.addProperty("url", playUrl);
                            }
                        }
                        // -----------------------------------------------------------
                        elm = propsObj.get("parse");
                        parse = elm == null ? 0 : elm.getAsInt();
                        elm = propsObj.get("jx");
                        jx = elm == null ? 0 : elm.getAsInt();
                        elm = propsObj.get("header");
                        if (elm == null) {
                            headers = Map.of();
                        } else {
                            headers = Maps.transformValues(
                                    GsonUtil.fromJson(elm.getAsString(), JsonObject.class).asMap(),
                                    JsonElement::getAsString
                            );
                        }
                        videoTitle = "《" + video.getName() + "》" + flag + " - " + urlInfoBean.getName();
                        if (parse == 0) {
                            if (ConfigHelper.getAdFilter() && playUrl.contains(".m3u8")) {
                                // 处理m3u8广告过滤、ts代理
                                filterAdAndProxy(playUrl, headers, isAdFilteredAndProxyUrl -> {
                                    Boolean isAdFiltered = isAdFilteredAndProxyUrl.getLeft();
                                    String proxyUrl = isAdFilteredAndProxyUrl.getRight();
                                    TVPlayBO tvPlayBO = TVPlayBO.of(
                                            proxyUrl,
                                            headers,
                                            videoTitle,
                                            progress,
                                            isAdFiltered
                                    );

                                    Platform.runLater(() -> {
                                        // --- 【新增代码：情况1】记录代理后的URL ---
                                        this.currentPlayUrl = proxyUrl;
                                        this.currentPlayHeaders = headers;
                                        this.currentVideoTitle = videoTitle;
                                        if (openInPotPlayerBtn != null) openInPotPlayerBtn.setDisable(false);
                                        // ------------------------------------

                                        // 更新详细信息 Tooltip
                                        updateInfo(selectedEpBtn, urlInfoBean.getUrl(), playerContentJson, proxyUrl, proxyUrl, headers, videoTitle);

                                        player.play(tvPlayBO);
                                    });
                                });
                            } else {
                                // --- 【新增代码：情况2】记录原始URL ---
                                this.currentPlayUrl = playUrl;
                                this.currentPlayHeaders = headers;
                                this.currentVideoTitle = videoTitle;
                                if (openInPotPlayerBtn != null) openInPotPlayerBtn.setDisable(false);
                                // ------------------------------------

                                // 更新详细信息 Tooltip
                                updateInfo(selectedEpBtn, urlInfoBean.getUrl(), playerContentJson, playUrl, playUrl, headers, videoTitle);

                                player.play(TVPlayBO.of(
                                        playUrl, headers, videoTitle, progress, false
                                ));
                            }
                        } else {
                            if (jx != 0) {
                                ToastHelper.showErrorI18n(I18nKeys.VIDEO_ERROR_SOURCE_NOT_SUPPORTED);
                                return;
                            }
                            player.setVideoTitle(videoTitle + " （此为解析源，请在弹出的浏览器程序中观看）");
                            HostServiceHelper.showDocument(playUrl);
                        }
                        playingVideo = video;
                        playingUrlInfo = urlInfo;
                        playingInfoBean = urlInfoBean;
                    })
        );
    }

    /**
     * 过滤广告并创建本地代理链接
     * @param playUrl 播放链接
     * @param headers 请求源m3u8需要携带的请求头
     * @param callback 回调。包含过滤广告成功标志和代理链接
     */
    private void filterAdAndProxy(
            String playUrl, Map<String, String> headers, Consumer<Pair<Boolean, String>> callback
    ) {
        AsyncUtil.execute(() -> {
            HttpRequest.Builder requestBuilder;
            M3u8AdFilterResult result;
            String content;
            HttpResponse<String> resp;
            Map<String, List<String>> proxyHeaders = null;
            boolean isAdFiltered = false;
            String playUrlForTsProxy;
            String proxyUrlPrefix;
            Pair<Boolean, String> proxyTsFlagAndProxiedM3u8Content;
            String resultPlayUrl;

            requestBuilder = HttpRequest.newBuilder()
                    .GET()
                    .headers(HttpHeaders.USER_AGENT, BaseValues.USER_AGENT)
                    .uri(HttpUtil.parseUrl(playUrl));
            if (!headers.isEmpty()) {
                headers.forEach(requestBuilder::header);
            }
            try {
                resp = HttpUtil.getClient()
                        .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                        .get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 下载m3u8失败，直接返回原地址
                callback.accept(Pair.of(false, playUrl));

                return;
            }
            proxyUrlPrefix = createProxyUrlPrefix();
            // 处理m3u8广告过滤
            try {
                content = resp.body();
                result = m3u8AdFilterHandler.handle(
                        playUrl,
                        content,
                        Map.of(SmartM3u8AdFilterHandler.EXTRA_KEY_DTF, ConfigHelper.getAdFilterDynamicThresholdFactor())
                );
                isAdFiltered = result.getAdLineCount() > 0;
                if (isAdFiltered) {
                    content = result.getContent();
                    proxyHeaders = Maps.filterKeys(
                            resp.headers().map(), key -> !HTTP_HEADERS_PROXY_EXCLUDE.contains(key)
                    );
                }
                playUrlForTsProxy = isAdFiltered ? proxyM3u8(content, proxyUrlPrefix, proxyHeaders) : playUrl;
            } catch (Exception e) {
                log.warn("filter ad exception", e);
                content = resp.body();
                playUrlForTsProxy = playUrl;
            }
            // 处理损坏文件头的ts代理
            try {
                proxyTsFlagAndProxiedM3u8Content =
                        m3u8TsProxyHandler.handle(
                                playUrlForTsProxy, content, proxyUrlPrefix + "/proxy/ts/"
                        );
                if (proxyTsFlagAndProxiedM3u8Content.getLeft()) {
                    content = proxyTsFlagAndProxiedM3u8Content.getRight();
                    if (proxyHeaders == null) {
                        proxyHeaders = Maps.filterKeys(
                                resp.headers().map(), key -> !HTTP_HEADERS_PROXY_EXCLUDE.contains(key)
                        );
                    }
                    resultPlayUrl = proxyM3u8(content, proxyUrlPrefix, proxyHeaders);
                } else {
                    resultPlayUrl = playUrlForTsProxy;
                }
            } catch (Exception e) {
                log.warn("proxy ts exception", e);
                resultPlayUrl = playUrlForTsProxy;
            }
            callback.accept(Pair.of(isAdFiltered, resultPlayUrl));
        });
    }

    private String createProxyUrlPrefix() {
        return "http://127.0.0.1:" + ConfigHelper.getHttpPort();
    }

    /**
     * 代理m3u8内容
     * @param m3u8Content m3u8内容
     * @param proxyUrlPrefix 代理前缀
     * @param proxyHeaders 代理请求头
     * @return 代理链接
     */
    private String proxyM3u8(String m3u8Content, String proxyUrlPrefix, Map<String, List<String>> proxyHeaders) {
        String proxyUrl = proxyUrlPrefix + "/proxy-cache/" + CacheKeys.AD_FILTERED_M3U8;

        CacheHelper.put(CacheKeys.AD_FILTERED_M3U8, m3u8Content);
        CacheHelper.put(
                CacheKeys.PROXY_CACHE_HTTP_HEADERS + CacheKeys.AD_FILTERED_M3U8,
                proxyHeaders
        );

        return proxyUrl;
    }

    @Override
    public void destroy() {
        AsyncUtil.execute(() -> {
            updatePlayInfo();
            onClose.accept(playInfo);
            player.destroy();
        });
        Context.INSTANCE.popAndShowLastStage();
    }

    private void updatePlayInfo() {
        String playFlag;

        if (playingVideo == null || playingUrlInfo == null || playingInfoBean == null) {
            return;
        }
        playFlag = playingUrlInfo.getFlag();
        if (playInfo == null) {
            playInfo = new VideoPlayInfoBO();
        }
        playInfo.setPlayFlag(playFlag);
        playInfo.setPlayIndex(playingUrlInfo.getBeanList().indexOf(playingInfoBean));
        playInfo.setProgress(player.getCurrentProgress());
        playInfo.setDuration(player.getCurrentDuration());
        playInfo.setPlayNote(selectedEpBtn.getText());
        CollectionUtil.findFirst(resourceTabPane.getTabs(), tab -> tab.getText().equals(playFlag))
                .ifPresent(tab -> {
                    ObservableList<MenuItem> menus = tab.getContextMenu().getItems();
                    CheckMenuItem reverseMenuItem = (CheckMenuItem) menus.get(0);

                    playInfo.setReverseSort(reverseMenuItem.isSelected());
                });
    }

    @FXML
    private void onCollectBtnAction() {
        VodInfo vodInfo;

        if (playingVideo == null) {

            return;
        }
        operationLoading.set(true);
        vodInfo = VodInfo.from(playingVideo);
        if (collectBtn.getGraphic() == COLLECTED_FONT_ICON) {
            template.deleteMovieCollection(
                    DeleteMovieCollectionDTO.of(vodInfo),
                    () -> {
                        setCollectBtnByCollectedStatus(false);
                        Platform.runLater(() -> ToastHelper.showSuccessI18n(I18nKeys.COMMON_MESSAGE_SUCCESS));
                    }
            );
        } else {
            template.saveMovieCollection(
                    SaveMovieCollectionDTO.of(vodInfo),
                    () -> {
                        setCollectBtnByCollectedStatus(true);
                        Platform.runLater(() -> ToastHelper.showSuccessI18n(I18nKeys.COMMON_MESSAGE_SUCCESS));
                    }
            );
        }
    }

    private void setCollectBtnByCollectedStatus(@Nullable Boolean collectedStatus) {
        Platform.runLater(() -> {
            if (BooleanUtils.toBoolean(collectedStatus)) {
                collectBtn.setGraphic(COLLECTED_FONT_ICON);
                collectBtn.setText(I18nHelper.get(I18nKeys.VIDEO_UN_COLLECT));
            } else {
                collectBtn.setGraphic(COLLECT_FONT_ICON);
                collectBtn.setText(I18nHelper.get(I18nKeys.VIDEO_COLLECT));
            }
            operationLoading.set(false);
        });
    }

    @FXML
    void onOpenInPotPlayerBtnAction() { // 不要带 ActionEvent 参数也可以，或者带上 import
        if (StringUtils.isBlank(currentPlayUrl)) {
            ToastHelper.showInfo("请先选择一集开始播放");
            return;
        }

        // 关键：强转为 VLCPlayer 调用我们写的新方法
        if (this.player instanceof VLCPlayer) {
            ((VLCPlayer) this.player).launchPotPlayer(currentPlayUrl, currentPlayHeaders, currentVideoTitle);
        } else {
            ToastHelper.showInfo("请在设置中将播放器切换为 VLC");
        }
    }

    private void updateInfo(
            Button btn,
            String rawUrl,
            JsonObject playerContentJson,
            String playUrl,
            String proxyUrl,
            Map<String, String> headers,
            String videoTitle
    ) {
        // 构建用于显示的文本（缩短 URL）
        String displayText = buildInfoContent(rawUrl, playerContentJson, playUrl, proxyUrl, headers, videoTitle, true);
        // 构建用于复制的文本（完整 URL）
        String copyText = buildInfoContent(rawUrl, playerContentJson, playUrl, proxyUrl, headers, videoTitle, false);

        // 设置 Tooltip
        Tooltip tooltip = new Tooltip(displayText);
        tooltip.setWrapText(true);
        tooltip.setPrefWidth(600);
        tooltip.setShowDelay(javafx.util.Duration.millis(200));
        tooltip.setShowDuration(javafx.util.Duration.INDEFINITE);
        tooltip.setStyle("-fx-font-size: 14px;");
        
        // 强制左侧显示
        tooltip.setOnShowing(ev -> {
            if (root.getScene() != null && root.getScene().getWindow() != null) {
                javafx.stage.Window window = root.getScene().getWindow();
                List<Screen> screens = Screen.getScreensForRectangle(
                        window.getX(), window.getY(), window.getWidth(), window.getHeight()
                );
                Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
                Rectangle2D bounds = screen.getVisualBounds();
                tooltip.setX(bounds.getMinX() + 20);
            }
        });

        if (btn != null) {
            btn.setTooltip(tooltip);
            
            // 更新右键菜单 (确保每个按钮有独立的复制内容)
            ContextMenu contextMenu = btn.getContextMenu();
            if (contextMenu == null) {
                contextMenu = new ContextMenu();
                btn.setContextMenu(contextMenu);
            }
            // 清除旧的“复制链接信息”项
            contextMenu.getItems().removeIf(item -> "复制链接信息".equals(item.getText()));
            MenuItem copyItem = new MenuItem("复制链接信息");
            copyItem.setOnAction(e -> {
                Platform.runLater(() -> {
                    try {
                        ClipboardContent content = new ClipboardContent();
                        content.putString(copyText);
                        Clipboard.getSystemClipboard().setContent(content);
                        ToastHelper.showInfo("已复制到剪贴板");
                    } catch (Exception ex) {
                        log.warn("Clipboard copy failed", ex);
                        ToastHelper.showError("复制失败，请重试");
                    }
                });
            });
            contextMenu.getItems().add(copyItem);
        }
        
        // 如果正在播放且是当前选中按钮，也更新播放器的 Tooltip (作为全局调试信息备份)
        if (player != null && btn == selectedEpBtn) {
            player.setVideoTitleTooltip(displayText);
            this.currentDebugInfo = copyText; // 兼容旧逻辑
        }
    }

    private String buildInfoContent(
            String rawUrl,
            JsonObject playerContentJson,
            String playUrl,
            String proxyUrl,
            Map<String, String> headers,
            String videoTitle,
            boolean forDisplay
    ) {
        // 清洗数据
        rawUrl = cleanUrl(rawUrl);
        playUrl = cleanUrl(playUrl);
        proxyUrl = cleanUrl(proxyUrl);

        StringBuilder sb = new StringBuilder();

        // 1. 【视频信息】 (对应图1：选集列表原始值)
        sb.append("【视频信息】\n");
        if (StringUtils.isNotBlank(rawUrl)) {
            try {
                JsonElement el = JsonParser.parseString(rawUrl);
                if (el.isJsonObject() || el.isJsonArray()) {
                    sb.append(formatAndShortenJson(el, forDisplay));
                } else {
                    sb.append(processUrlStr(rawUrl, forDisplay));
                }
            } catch (Exception e) {
                sb.append(processUrlStr(rawUrl, forDisplay));
            }
        } else {
            sb.append("无");
        }
        sb.append("\n\n");

        // 2. 【解析详情】 (对应图2第一部分：接口返回的完整JSON)
        if (playerContentJson != null) {
            sb.append("【解析详情】\n");
            // 对整个 JSON 对象进行深拷贝清洗和缩短
            sb.append(formatAndShortenJson(playerContentJson, forDisplay));
            sb.append("\n\n");
        }

        // 3. 【中转链】
        if (StringUtils.isNotBlank(proxyUrl)) {
            sb.append("【中转链】\n");
            // 第一行：原始（编码后）的链接
            sb.append(processUrlStr(proxyUrl, forDisplay)).append("\n");
            
            // 尝试解码
            try {
                String decodedUrl = URLDecoder.decode(proxyUrl, StandardCharsets.UTF_8);
                decodedUrl = cleanUrl(decodedUrl); 
                
                // 只有当解码后的链接与原始链接确实不同时，才显示第二行
                // 比较时移除所有空白字符，防止因换行等不可见字符导致误判
                String cleanProxy = proxyUrl.replaceAll("\\s+", "");
                String cleanDecoded = decodedUrl.replaceAll("\\s+", "");
                
                // 确保确实不同（如果 cleanProxy 本身就是解码状态，或者解码前后一致，则不显示第二行）
                if (StringUtils.isNotBlank(decodedUrl) && !cleanProxy.equalsIgnoreCase(cleanDecoded)) {
                    sb.append("\n");
                    sb.append(processUrlStr(decodedUrl, forDisplay)).append("\n");
                }
            } catch (Exception ignored) {
            }
            sb.append("\n");
        }
        
        // 5. 【PotPlayer】
        // 确定最终用于播放的 URL (优先使用中转链，没有则用解析链接)
        String finalPlayUrl = StringUtils.isNotBlank(proxyUrl) ? proxyUrl : playUrl;
        
        if (StringUtils.isNotBlank(finalPlayUrl)) {
             sb.append("【PotPlayer】\n");
             String potPath = ConfigHelper.findPotPlayerPath();
             if (potPath == null) potPath = "PotPlayerMini64.exe";
             
             String urlForCmd = forDisplay ? shortenUrl(finalPlayUrl) : finalPlayUrl;
             
             StringBuilder cmdSb = new StringBuilder("& ");
             cmdSb.append("\"").append(potPath).append("\" \"");
             cmdSb.append(urlForCmd).append("\"");
             
             if (headers != null) {
                 String ua = headers.getOrDefault("User-Agent", "");
                 String ref = headers.getOrDefault("Referer", "");
                 if (!ua.isEmpty()) cmdSb.append(" /user_agent=\"").append(ua).append("\"");
                 if (!ref.isEmpty()) cmdSb.append(" /referrer=\"").append(ref).append("\"");
             }
             
             if (StringUtils.isNotBlank(videoTitle)) {
                 String safeTitle = videoTitle.replace("\"", "\\\"");
                 cmdSb.append(" /title=\"").append(safeTitle).append("\"");
             }
             
             sb.append(cmdSb.toString());
        }

        return sb.toString();
    }
    
    private String cleanUrl(String url) {
        if (url == null) return null;
        // 去除反引号和首尾空白
        return url.replace("`", "").trim();
    }
    
    private String processUrlStr(String url, boolean forDisplay) {
        if (!forDisplay) return url;
        return shortenUrl(url);
    }

    private String shortenUrl(String url) {
        if (url == null || url.length() <= 100) return url;
        return url.substring(0, 60) + "..." + url.substring(url.length() - 30);
    }

    private String formatAndShortenJson(JsonElement el, boolean forDisplay) {
        // 无论是显示还是复制，都建议清洗一下反引号（除非用户想保留反引号，但通常那是脏数据）
        // 这里我们对 JSON 数据进行深拷贝处理
        el = el.deepCopy();
        processJsonValues(el, forDisplay);
        return GsonUtil.toPrettyJson(el);
    }

    private void processJsonValues(JsonElement el, boolean forDisplay) {
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                    String str = val.getAsString();
                    // 1. 清洗反引号
                    String cleaned = cleanUrl(str);
                    
                    // 2. 如果是显示模式且超长，则缩短
                    if (forDisplay && cleaned.length() > 100 && (cleaned.startsWith("http") || cleaned.contains("://"))) {
                        cleaned = shortenUrl(cleaned);
                    }
                    
                    // 更新值
                    if (!str.equals(cleaned)) {
                        entry.setValue(new com.google.gson.JsonPrimitive(cleaned));
                    }
                } else if (val.isJsonObject() || val.isJsonArray()) {
                    processJsonValues(val, forDisplay);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                processJsonValues(item, forDisplay);
            }
        }
    }
}
