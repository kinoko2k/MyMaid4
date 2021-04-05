/*
 * jaoLicense
 *
 * Copyright (c) 2021 jao Minecraft Server
 *
 * The following license applies to this project: jaoLicense
 *
 * Japanese: https://github.com/jaoafa/jao-Minecraft-Server/blob/master/jaoLICENSE.md
 * English: https://github.com/jaoafa/jao-Minecraft-Server/blob/master/jaoLICENSE-en.md
 */

package com.jaoafa.mymaid4.lib;

import com.jaoafa.mymaid4.Main;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.TextChannel;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;

/**
 * EBan Library
 */
public class EBan {
    static Map<UUID, EBan> cache = new HashMap<>();

    OfflinePlayer player;

    /** EBan Id */
    private int id = -1;
    /** 処罰者 */
    private String banned_by = null;
    /** 理由 */
    private String reason = null;
    /** 解除者 */
    private String remover = null;
    /** 処罰中か */
    private boolean status = false;
    /** データ作成時刻 */
    private Timestamp created_at = null;
    /** フェッチ日時 */
    private long dbSyncedTime = -1L;

    private EBan(OfflinePlayer player) {
        this.player = player;
    }

    public static EBan getInstance(OfflinePlayer player) {
        return getInstance(player, false);
    }

    public static EBan getInstance(OfflinePlayer player, boolean force) {
        EBan eban = cache.get(player.getUniqueId());
        if (eban == null) {
            eban = new EBan(player);
        }
        eban.fetchData(force);
        return eban;
    }

    /**
     * 現在EBanされているプレイヤーの一覧を返します。
     *
     * @return 現在EBanされているプレイヤー
     */
    public static List<OfflinePlayer> getBannedPlayers() {
        List<OfflinePlayer> ebans = new ArrayList<>();

        try {
            Connection conn = MyMaidData.getMainMySQLDBManager().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT uuid FROM eban WHERE status = ?")) {
                stmt.setBoolean(1, true);

                ResultSet res = stmt.executeQuery();
                while (res.next()) {
                    ebans.add(Bukkit.getOfflinePlayer(UUID.fromString(res.getString("uuid"))));
                }

                return ebans;
            }
        } catch (SQLException e) {
            MyMaidLibrary.reportError(Jail.class, e);
            return null;
        }
    }

    /**
     * このユーザーをEBanに追加します。
     *
     * @param banned_by Banを実行した実行者情報
     * @param reason    理由
     *
     * @return Result
     */
    public Result addBan(String banned_by, String reason) {
        if (!MyMaidData.isMainDBActive()) {
            return Result.DATABASE_NOT_ACTIVE;
        }
        if (isStatus()) {
            return Result.ALREADY;
        }
        try {
            Connection conn = MyMaidData.getMainMySQLDBManager().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO eban (player, uuid, banned_by, reason, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                stmt.setString(1, player.getName());
                stmt.setString(2, player.getUniqueId().toString());
                stmt.setString(3, banned_by);
                stmt.setString(4, reason);
                boolean isSuccess = stmt.executeUpdate() == 1;
                if (!isSuccess) {
                    return Result.UNKNOWN_ERROR;
                }

                String displayName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
                Bukkit.getServer().sendMessage(Component.text().append(
                    Component.text("[EBan]"),
                    Component.space(),
                    Component.text("プレイヤー「", NamedTextColor.GREEN),
                    Component.text(displayName, NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showEntity(Key.key("player"), player.getUniqueId(), Component.text(displayName))),
                    Component.text("」が「", NamedTextColor.GREEN),
                    Component.text(reason, NamedTextColor.GREEN),
                    Component.text("」という理由でEBanされました。", NamedTextColor.GREEN)
                ));

                TextChannel sendTo = getDiscordSendTo();
                sendTo.sendMessage(
                    String.format("__**EBan[追加]**__: プレイヤー「%s」が「%s」によって「%s」という理由でEBanされました。",
                        MyMaidLibrary.DiscordEscape(player.getName()),
                        MyMaidLibrary.DiscordEscape(banned_by),
                        MyMaidLibrary.DiscordEscape(reason))).queue();
                if (MyMaidData.getServerChatChannel() != null) {
                    MyMaidData.getServerChatChannel().sendMessage(
                        String.format("__**EBan[追加]**__: プレイヤー「%s」が「%s」によって「%s」という理由でEBanされました。",
                            MyMaidLibrary.DiscordEscape(player.getName()),
                            MyMaidLibrary.DiscordEscape(banned_by),
                            MyMaidLibrary.DiscordEscape(reason))).queue();
                }

                if (player.isOnline() && player.getPlayer() != null) {
                    if (player.getPlayer().getGameMode() == GameMode.SPECTATOR) {
                        player.getPlayer().setGameMode(GameMode.CREATIVE);
                    }

                    World Jao_Afa = Bukkit.getServer().getWorld("Jao_Afa");
                    Location minami = new Location(Jao_Afa, 2856, 69, 2888);
                    player.getPlayer().teleport(minami);
                }

                fetchData(true);
                return Result.SUCCESS;
            }
        } catch (SQLException e) {
            MyMaidLibrary.reportError(getClass(), e);
            return Result.DATABASE_ERROR;
        }
    }

    /**
     * このユーザーのEBanを解除します。
     *
     * @param remover 解除者
     *
     * @return Result
     */
    public Result removeBan(String remover) {
        if (!MyMaidData.isMainDBActive()) {
            return Result.DATABASE_NOT_ACTIVE;
        }
        if (!isStatus()) {
            return Result.ALREADY;
        }
        try {
            Connection conn = MyMaidData.getMainMySQLDBManager().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE eban SET status = ?, remover = ? WHERE uuid = ? ORDER BY id DESC LIMIT 1")) {
                stmt.setBoolean(1, false);
                stmt.setString(2, remover);
                stmt.setString(3, player.getUniqueId().toString());
                boolean isSuccess = stmt.executeUpdate() == 1;
                if (!isSuccess) {
                    return Result.UNKNOWN_ERROR;
                }

                String displayName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
                Bukkit.getServer().sendMessage(Component.text().append(
                    Component.text("[EBan]"),
                    Component.space(),
                    Component.text("プレイヤー「", NamedTextColor.GREEN),
                    Component.text(displayName, NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showEntity(Key.key("player"), player.getUniqueId(), Component.text(displayName))),
                    Component.text("」のEBanを解除しました。", NamedTextColor.GREEN)
                ));

                TextChannel sendTo = getDiscordSendTo();
                sendTo.sendMessage(
                    String.format("__**EBan[解除]**__: プレイヤー「%s」のEBanを「%s」によって解除されました。",
                        MyMaidLibrary.DiscordEscape(player.getName()), MyMaidLibrary.DiscordEscape(remover)))
                    .queue();
                if (MyMaidData.getServerChatChannel() != null) {
                    MyMaidData.getServerChatChannel().sendMessage(
                        String.format("__**EBan[解除]**__: プレイヤー「%s」のEBanを「%s」によって解除されました。",
                            MyMaidLibrary.DiscordEscape(player.getName()), MyMaidLibrary.DiscordEscape(remover))).queue();
                }

                fetchData(true);
                return Result.SUCCESS;
            }
        } catch (SQLException e) {
            MyMaidLibrary.reportError(getClass(), e);
            return Result.DATABASE_ERROR;
        }
    }

    /**
     * 各種EBan通知の送信先を返します。
     *
     * @return 送信先
     */
    TextChannel getDiscordSendTo() {
        JDA jda = Main.getMyMaidConfig().getJDA();
        if (jda == null) {
            return null;
        }
        if (MyMaidLibrary.isAMR(player)) {
            return jda.getTextChannelById(690854369783971881L); // #rma_eban
        } else {
            return jda.getTextChannelById(709399145575874690L); // #eban
        }
    }

    /**
     * データをフェッチします。forceがFalseの場合、最終情報取得から1時間経過していない場合キャッシュ情報を利用します。
     *
     * @param force 強制的にフェッチするか
     *
     * @return FetchDataResult
     */
    public FetchDataResult fetchData(boolean force) {
        if (!force && ((dbSyncedTime + 60 * 60 * 1000) > System.currentTimeMillis())) {
            return FetchDataResult.CACHED; // 30分未経過
        }
        if (!MyMaidData.isMainDBActive()) {
            return FetchDataResult.DATABASE_NOT_ACTIVE;
        }
        try {
            Connection conn = MyMaidData.getMainMySQLDBManager().getConnection();

            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM eban WHERE uuid = ? ORDER BY id DESC LIMIT 1");
            stmt.setString(1, player.getUniqueId().toString());

            try (ResultSet res = stmt.executeQuery()) {
                this.dbSyncedTime = System.currentTimeMillis();
                if (!res.next()) {
                    cache.put(player.getUniqueId(), this);
                    return FetchDataResult.NOTFOUND;
                }

                this.id = res.getInt("id");
                this.banned_by = res.getString("banned_by");
                this.reason = res.getString("reason");
                this.remover = res.getString("remover");
                this.status = res.getBoolean("status");
                this.created_at = res.getTimestamp("created_at");

                cache.put(player.getUniqueId(), this);
            }

            return FetchDataResult.SUCCESS;
        } catch (SQLException e) {
            MyMaidLibrary.reportError(getClass(), e);
            return FetchDataResult.DATABASE_ERROR;
        }
    }

    /**
     * EBanIdを取得します。-1の場合データが存在しないか、フェッチされていません。
     *
     * @return EBanId
     */
    public int getEBanId() {
        return id;
    }

    /**
     * プレイヤーを取得します。
     *
     * @return プレイヤー
     */
    @Nullable
    public OfflinePlayer getPlayer() {
        return player;
    }


    /**
     * 処罰者名を取得します。
     *
     * @return 処罰者名
     */
    @Nullable
    public String getBannedBy() {
        return banned_by;
    }

    /**
     * 処罰理由を取得します。
     *
     * @return 処罰理由
     */
    @Nullable
    public String getReason() {
        return reason;
    }

    /**
     * 解除者名を取得します。
     *
     * @return 解除者名
     */
    @Nullable
    public String getRemover() {
        return remover;
    }

    /**
     * 現在の状態を取得します。
     *
     * @return 現在の状態。Trueの場合処罰中。
     */
    public boolean isStatus() {
        return status;
    }

    /**
     * データ作成日時(処罰日時)を取得します。
     *
     * @return データ作成日時
     */
    @Nullable
    public Timestamp getCreatedAt() {
        return created_at;
    }

    public enum Result {
        /** 成功 */
        SUCCESS,
        /** 既に処理済 */
        ALREADY,
        /** 未処罰済 (Testmentの際) */
        NOT_BANNED,
        /** データベースが無効または接続不能 */
        DATABASE_NOT_ACTIVE,
        /** データベース通信時にエラー */
        DATABASE_ERROR,
        /** 不明なエラー */
        UNKNOWN_ERROR
    }

    enum FetchDataResult {
        /** 成功 */
        SUCCESS,
        /** データなし */
        NOTFOUND,
        /** データベースが無効または接続不能 */
        DATABASE_NOT_ACTIVE,
        /** データベース通信時にエラー */
        DATABASE_ERROR,
        /** キャッシュから取得 */
        CACHED,
        /** 不明なエラー */
        UNKNOWN
    }
}
