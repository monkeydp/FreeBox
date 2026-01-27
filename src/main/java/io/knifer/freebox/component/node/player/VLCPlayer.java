package io.knifer.freebox.component.node.player;

import cn.hutool.core.util.IdUtil;
import io.knifer.freebox.helper.ConfigHelper;
import io.knifer.freebox.helper.SystemHelper;
import io.knifer.freebox.helper.WindowHelper;
import io.knifer.freebox.util.AsyncUtil;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.fullscreen.JavaFXFullScreenStrategy;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.base.SubpictureApi;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * VLC播放器自定义组件 (已增强：支持Header透传及PotPlayer外调)
 *
 * @author Knifer & AI Optimizer
 */
@Slf4j
public class VLCPlayer extends BasePlayer<ImageView> {

    private volatile int trackId = -1;
    private final EmbeddedMediaPlayer mediaPlayer;
    private final AtomicLong playingResourceId = new AtomicLong();
    private final Lock playingResourceLock = new ReentrantLock();
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    public VLCPlayer(Pane parent, Config config) {
        super(parent, config);

        if (BooleanUtils.isNotFalse(config.getExternalMode())) {
            config.setExternalMode(false);
        }

        Stage stage = WindowHelper.getStage(parent);
        MediaPlayerFactory mediaPlayerFactory;
        List<String> factoryArgs = new ArrayList<>();

        if (SystemHelper.isDebug()) {
            factoryArgs.add("-vvv");
        }

        // 自动应用系统代理设置 (JVM properties) 到 VLC
        String proxyHost = System.getProperty("http.proxyHost");
        String proxyPort = System.getProperty("http.proxyPort");
        if (StringUtils.isNotBlank(proxyHost) && StringUtils.isNotBlank(proxyPort)) {
            factoryArgs.add("--http-proxy=" + proxyHost + ":" + proxyPort);
            log.info("VLCPlayer using proxy: {}:{}", proxyHost, proxyPort);
        }

        mediaPlayerFactory = new MediaPlayerFactory(factoryArgs);
        mediaPlayer = mediaPlayerFactory.mediaPlayers()
                                        .newEmbeddedMediaPlayer();
        mediaPlayer.fullScreen()
                   .strategy(new JavaFXFullScreenStrategy(stage) {
                       @Override
                       public void onBeforeEnterFullScreen() {
                           Platform.runLater(() -> postEnterFullScreen());
                       }

                       @Override
                       public void onAfterExitFullScreen() {
                           Platform.runLater(() -> postExitFullScreen());
                       }
                   });
        mediaPlayer.events()
                   .addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                       @Override
                       public void mediaPlayerReady(MediaPlayer mediaPlayer) {
                           Platform.runLater(() -> setLoading(false));
                       }

                       @Override
                       public void buffering(MediaPlayer mediaPlayer, float newCache) {
                           Platform.runLater(() -> postBuffering(newCache));
                       }

                       @Override
                       public void paused(MediaPlayer mediaPlayer) {
                           Platform.runLater(() -> postPaused());
                       }

                       @Override
                       public void playing(MediaPlayer mediaPlayer) {
                           Platform.runLater(() -> postPlaying());
                       }

                       @Override
                       @SuppressWarnings("ConstantConditions")
                       public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                           Platform.runLater(() -> postLengthChanged(newLength));
                       }

                       @Override
                       @SuppressWarnings("ConstantConditions")
                       public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                           Platform.runLater(() -> postTimeChanged(newTime));
                       }

                       @Override
                       public void finished(MediaPlayer mediaPlayer) {
                           postFinished();
                       }

                       @Override
                       public void error(MediaPlayer mediaPlayer) {
                           Platform.runLater(() -> postError());
                       }
                   });
        mediaPlayer.videoSurface()
                   .set(new ImageViewVideoSurface(playerNode));
    }

    @Override
    protected ImageView createPlayerNode() {
        ImageView videoImageView = new ImageView();
        videoImageView.setPreserveRatio(true);
        videoImageView.fitWidthProperty()
                      .bind(playerPane.widthProperty());
        videoImageView.fitHeightProperty()
                      .bind(playerPane.heightProperty());
        return videoImageView;
    }

    @Override
    protected void movePosition(boolean forward) {
        long length = mediaPlayer.status()
                                 .length();
        if (!mediaPlayer.status()
                        .isPlayable() || length <= 0) return;
        long oldTime = mediaPlayer.status()
                                  .time();
        long newTime = forward ? Math.min(oldTime + 5000, length) : Math.max(oldTime - 5000, 0);
        mediaPlayer.controls()
                   .setTime(newTime);
        postMovedPosition();
    }

    @Override
    protected void moveVolume(boolean forward) {
        AsyncUtil.execute(() -> {
            double oldVolume = mediaPlayer.audio()
                                          .volume();
            double newVolume = forward ? Math.min(oldVolume + 10, 100) : Math.max(oldVolume - 10, 0);
            if (mediaPlayer.audio()
                           .isMute()) {
                mediaPlayer.audio()
                           .mute();
                Platform.runLater(() -> {
                    postMuted();
                    postMovedVolume(newVolume);
                });
            } else {
                Platform.runLater(() -> postMovedVolume(newVolume));
            }
        });
    }

    @Override
    protected boolean doPlay(String url, Map<String, String> headers, String videoTitle, @Nullable Long progress) {
        if (!super.doPlay(url, headers, videoTitle, progress)) {
            return false;
        }

        String[] options = parsePlayOptionsFromHeaders(headers);
        long playingResourceId = IdUtil.getSnowflakeNextId();
        this.playingResourceId.set(playingResourceId);
        log.info("开始内置播放 - url={}, options={}", url, options);

        playbackExecutor.execute(() -> {
            try {
                playingResourceLock.lock();
                if (playingResourceId != this.playingResourceId.get()) {
                    return;
                }
                mediaPlayer.media().play(url, options);
            } finally {
                playingResourceLock.unlock();
            }
        });
        return true;
    }

    // 2. 新增的外调方法（供按钮调用）
    public void launchPotPlayer(String url, Map<String, String> headers, String title) {
        if (StringUtils.isBlank(url)) return;
        try {
            String potPath = ConfigHelper.findPotPlayerPath();
            if (potPath == null) {
                log.warn("未找到 PotPlayer");
                return;
            }
            File potExe = new File(potPath);
            if (!potExe.exists()) {
                log.warn("未找到 PotPlayer");
                return;
            }

            // 组装参数
            String ua = (headers != null) ? headers.getOrDefault("User-Agent", "") : "";
            String ref = (headers != null) ? headers.getOrDefault("Referer", "") : "";
            List<String> command = new ArrayList<>();
            command.add(potPath);
            command.add(url);
            if (!ua.isEmpty()) command.add("/user_agent=\"" + ua + "\"");
            if (!ref.isEmpty()) command.add("/referrer=\"" + ref + "\"");
            command.add("/title=\"" + title + "\"");

            // 启动外部播放
            new ProcessBuilder(command).start();

            // 停止内部播放
            this.stop();
        } catch (Exception e) {
            log.error("外调 PotPlayer 失败", e);
        }
    }

    /**
     * 尝试启动外部 PotPlayer
     */
    private boolean tryPlayInPotPlayer(String url, Map<String, String> headers) {
        try {
            String potPath = ConfigHelper.findPotPlayerPath();
            if (potPath == null) {
                log.warn("未找到 PotPlayer 路径, 请检查配置");
                return false;
            }
            File potExe = new File(potPath);
            if (!potExe.exists()) {
                log.warn("未找到 PotPlayer 路径: {}, 请检查配置", potPath);
                return false;
            }

            String ua = "";
            String ref = "";
            if (headers != null) {
                ua = headers.getOrDefault("User-Agent", "");
                ref = headers.getOrDefault("Referer", "");
            }

            ProcessBuilder pb = new ProcessBuilder(
                    potPath,
                    url,
                    "/user_agent=\"" + ua + "\"",
                    "/referrer=\"" + ref + "\""
            );
            log.info("启动 PotPlayer 命令参数: {}", pb.command());
            pb.start();
            return true;
        } catch (Exception e) {
            log.error("外调 PotPlayer 失败", e);
            return false;
        }
    }

    /**
     * 解析 Headers 为 VLC 实例参数 (重点：使用冒号 : 前缀)
     */
    @Nullable
    private String[] parsePlayOptionsFromHeaders(Map<String, String> headers) {
        List<String> options = new ArrayList<>();
        options.add("--subsdec-encoding=UTF-8"); // 全局参数

        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey()
                                  .toLowerCase();
                String value = entry.getValue();

                // VLC 针对特定媒体流的参数必须以冒号 : 开头
                if (key.equals("user-agent")) {
                    options.add(":http-user-agent=" + value);
                } else if (key.equals("referer")) {
                    options.add(":http-referrer=" + value);
                }
            }
        }
        return options.toArray(new String[0]);
    }

    @Override
    public void togglePause() {
        if (isError() || isLoading()) return;
        AsyncUtil.execute(() -> {
            if (mediaPlayer.status()
                           .canPause()) {
                Platform.runLater(() -> setLoading(true));
                mediaPlayer.controls()
                           .pause();
            }
        });
    }

    public void stop() {
        setLoading(false);
        AsyncUtil.execute(() -> {
            State playerState = mediaPlayer.status()
                                           .state();
            if (playerState != State.STOPPED && playerState != State.ENDED) {
                mediaPlayer.controls()
                           .stop();
            }
        });
    }

    @Override
    public long getCurrentProgress() {
        long length = mediaPlayer.status()
                                 .length();
        if (length < 1) return 0;
        float position = mediaPlayer.status()
                                    .position();
        return position == 0 ? 0 : ((long) (position * length));
    }

    @Override
    public void destroy() {
        super.destroy();
        if (mediaPlayer.status()
                       .isPlaying()) {
            SystemHelper.allowSleep();
        }
        playbackExecutor.shutdownNow();
        mediaPlayer.release();
        log.info("vlc media player released");
    }

    @Override
    protected void setPlayTime(long time) {
        AsyncUtil.execute(() -> mediaPlayer.controls()
                                           .setTime(time));
    }

    @Override
    protected void setVolume(int volume) {
        AsyncUtil.execute(() -> mediaPlayer.audio()
                                           .setVolume(volume));
    }

    @Override
    protected void setSubtitleDelay(long delay) {
        AsyncUtil.execute(() -> mediaPlayer.subpictures()
                                           .setDelay(delay * 1000));
    }

    @Override
    protected boolean useSubtitle(File subtitleFile) {
        if (mediaPlayer.status()
                       .isPlayable()) {
            return mediaPlayer.subpictures()
                              .setSubTitleFile(subtitleFile);
        }
        return false;
    }

    @Override
    protected void setSubtitleVisible(boolean visible) {
        SubpictureApi mediaPlayerSubpictureApi = mediaPlayer.subpictures();
        AsyncUtil.execute(() -> {
            if (visible) {
                if (mediaPlayerSubpictureApi.track() == -1) {
                    if (trackId != -1) {
                        mediaPlayerSubpictureApi.setTrack(trackId);
                    } else {
                        mediaPlayerSubpictureApi.trackDescriptions()
                                                .stream()
                                                .filter(td -> td.id() != -1)
                                                .findFirst()
                                                .ifPresent(td -> {
                                                    trackId = td.id();
                                                    mediaPlayerSubpictureApi.setTrack(trackId);
                                                });
                    }
                }
            } else if ((trackId = mediaPlayerSubpictureApi.track()) != -1) {
                mediaPlayerSubpictureApi.setTrack(-1);
            }
        });
    }

    @Override
    protected void toggleMute() {
        AsyncUtil.execute(() -> mediaPlayer.audio()
                                           .mute());
    }

    @Override
    protected boolean isMute() {
        return mediaPlayer.audio()
                          .isMute();
    }

    @Override
    protected void setRate(float rate) {
        mediaPlayer.controls()
                   .setRate(rate);
    }

    @Override
    protected void setFillWindow(boolean fillWindow) {
        playerNode.setPreserveRatio(!fillWindow);
    }

    @Override
    protected void play() {
        AsyncUtil.execute(() -> mediaPlayer.controls()
                                           .play());
    }

    @Override
    protected void toggleFullScreen() {
        mediaPlayer.fullScreen()
                   .toggle();
    }

    @Override
    protected boolean isFullScreen() {
        return mediaPlayer.fullScreen()
                          .isFullScreen();
    }

    @Override
    protected boolean isSeekable() {
        return mediaPlayer.status()
                          .isPlayable() && mediaPlayer.status()
                                                      .isSeekable();
    }

    @Override
    protected void setPositionPercent(float positionPercent) {
        mediaPlayer.controls()
                   .setPosition(positionPercent);
    }
}