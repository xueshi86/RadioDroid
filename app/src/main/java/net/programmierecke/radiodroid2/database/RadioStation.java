package net.programmierecke.radiodroid2.database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "radio_stations",
        indices = {
                @Index(value = {"name"}),
                @Index(value = {"clickcount"}),
                @Index(value = {"votes"}),
                @Index(value = {"lastchangetime"}),
                @Index(value = {"lastclicktime"}),
                @Index(value = {"country"}),
                @Index(value = {"countrycode"}),
                @Index(value = {"language"}),
                @Index(value = {"tags"})
        })
public class RadioStation {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "station_uuid")
    public String stationUuid;

    @ColumnInfo(name = "change_uuid")
    public String changeUuid;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "url")
    public String url;

    @ColumnInfo(name = "homepage")
    public String homepage;

    @ColumnInfo(name = "favicon")
    public String favicon;

    @ColumnInfo(name = "country")
    public String country;

    @ColumnInfo(name = "countrycode")
    public String countrycode;

    @ColumnInfo(name = "state")
    public String state;

    @ColumnInfo(name = "tags")
    public String tags;

    @ColumnInfo(name = "language")
    public String language;

    @ColumnInfo(name = "clickcount")
    public int clickcount;

    @ColumnInfo(name = "clicktrend")
    public int clicktrend;

    @ColumnInfo(name = "votes")
    public int votes;

    @ColumnInfo(name = "bitrate")
    public int bitrate;

    @ColumnInfo(name = "codec")
    public String codec;

    @ColumnInfo(name = "lastcheckok")
    public boolean lastcheckok;

    @ColumnInfo(name = "hls")
    public boolean hls;

    @ColumnInfo(name = "lastchangetime")
    public String lastchangetime;

    @ColumnInfo(name = "lastclicktime")
    public String lastclicktime;

    public RadioStation() {
    }

    // 从DataRadioStation转换为RadioStation
    public static RadioStation fromDataRadioStation(net.programmierecke.radiodroid2.station.DataRadioStation dataStation) {
        RadioStation station = new RadioStation();
        station.stationUuid = dataStation.StationUuid;
        station.changeUuid = dataStation.ChangeUuid;
        station.name = dataStation.Name;
        station.url = dataStation.StreamUrl;
        station.homepage = dataStation.HomePageUrl;
        station.favicon = dataStation.IconUrl;
        station.country = dataStation.Country;
        station.countrycode = dataStation.CountryCode;
        station.state = dataStation.State;
        station.tags = dataStation.TagsAll;
        station.language = dataStation.Language;
        station.clickcount = dataStation.ClickCount;
        station.clicktrend = dataStation.ClickTrend;
        station.votes = dataStation.Votes;
        station.bitrate = dataStation.Bitrate;
        station.codec = dataStation.Codec;
        station.lastcheckok = dataStation.Working;
        station.hls = dataStation.Hls;
        station.lastchangetime = dataStation.LastChangeTime;
        // DataRadioStation中没有LastClickTime字段，暂时不设置
        return station;
    }

    // 转换为DataRadioStation
    public net.programmierecke.radiodroid2.station.DataRadioStation toDataRadioStation() {
        net.programmierecke.radiodroid2.station.DataRadioStation dataStation = new net.programmierecke.radiodroid2.station.DataRadioStation();
        dataStation.StationUuid = this.stationUuid;
        dataStation.ChangeUuid = this.changeUuid;
        dataStation.Name = this.name;
        dataStation.StreamUrl = this.url;
        dataStation.HomePageUrl = this.homepage;
        dataStation.IconUrl = this.favicon;
        dataStation.Country = this.country;
        dataStation.CountryCode = this.countrycode;
        dataStation.State = this.state;
        dataStation.TagsAll = this.tags;
        dataStation.Language = this.language;
        dataStation.ClickCount = this.clickcount;
        dataStation.ClickTrend = this.clicktrend;
        dataStation.Votes = this.votes;
        dataStation.Bitrate = this.bitrate;
        dataStation.Codec = this.codec;
        dataStation.Working = this.lastcheckok;
        dataStation.Hls = this.hls;
        dataStation.LastChangeTime = this.lastchangetime;
        // DataRadioStation中没有LastClickTime字段，暂时不设置
        return dataStation;
    }
}