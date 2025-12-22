package net.programmierecke.radiodroid2.interfaces;

import net.programmierecke.radiodroid2.station.StationsFilter;

public interface IFragmentSearchable {
    void search(StationsFilter.SearchStyle searchStyle, String query);
}
