package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import net.programmierecke.radiodroid2.interfaces.IFragmentRefreshable;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.station.FragmentCategoriesLocal;
import net.programmierecke.radiodroid2.station.FragmentLocalStations;
import net.programmierecke.radiodroid2.station.FragmentRecentlyChanged;
import net.programmierecke.radiodroid2.station.FragmentSearchLocal;
import net.programmierecke.radiodroid2.station.FragmentStations;
import net.programmierecke.radiodroid2.station.FragmentTopClick;
import net.programmierecke.radiodroid2.station.FragmentTopVote;
import net.programmierecke.radiodroid2.station.StationsFilter;

import java.util.ArrayList;
import java.util.List;

public class FragmentTabs extends Fragment implements IFragmentRefreshable, IFragmentSearchable {

    // Note: the actual order of tabs is defined
    // further down when populating the ViewPagerAdapter
    private static final int IDX_LOCAL = 0;
    private static final int IDX_TOP_CLICK = 1;
    private static final int IDX_TOP_VOTE = 2;
    private static final int IDX_CHANGED_LATELY = 3;
    private static final int IDX_MULTI_SEARCH = 4;

    public static ViewPager viewPager;

    private String queuedSearchQuery;
    private StationsFilter.SearchStyle queuedSearchStyle;

    private Fragment[] fragments = new Fragment[5];
    private ViewPagerAdapter viewPagerAdapter;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View x = inflater.inflate(R.layout.layout_tabs, null);
        final TabLayout tabLayout = getActivity().findViewById(R.id.tabs);
        viewPager = (ViewPager) x.findViewById(R.id.viewpager);

        setupViewPager(viewPager);

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                if (getActivity() != null) {
                    getActivity().invalidateOptionsMenu();
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });

        if (queuedSearchQuery != null) {
            Log.d("TABS", "do queued search by name:"+ queuedSearchQuery);
            search(queuedSearchStyle, queuedSearchQuery);
            queuedSearchQuery = null;
            queuedSearchStyle = StationsFilter.SearchStyle.ByName;
        }

        /*
         * Now , this is a workaround ,
         * The setupWithViewPager doesn't works without the runnable .
         * Maybe a Support Library Bug .
         */

        tabLayout.post(new Runnable() {
            @Override
            public void run() {
                if(getContext() != null)
                    tabLayout.setupWithViewPager(viewPager);
            }
        });

        return x;
    }

    @Override
    public void onResume() {
        super.onResume();

        final TabLayout tabLayout = getActivity().findViewById(R.id.tabs);
        tabLayout.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPause() {
        super.onPause();

        final TabLayout tabLayout = getActivity().findViewById(R.id.tabs);
        tabLayout.setVisibility(View.GONE);
    }

    public Fragment getCurrentVisibleFragment() {
        if (viewPager != null && viewPagerAdapter != null) {
            int currentItem = viewPager.getCurrentItem();
            if (currentItem >= 0 && currentItem < viewPagerAdapter.mFragmentList.size()) {
                return viewPagerAdapter.mFragmentList.get(currentItem);
            }
        }
        return null;
    }

    private String getCountryCode() {
        Context ctx = getContext();
        String countryCode = null;
        if (ctx != null) {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            countryCode = tm.getNetworkCountryIso();
            Log.d("MAIN", "Network country code: '" + countryCode + "'");
            if (countryCode != null && countryCode.length() == 2) {
                return countryCode;
            }
            countryCode = tm.getSimCountryIso();
            Log.d("MAIN", "Sim country code: '" + countryCode + "'");
            if (countryCode != null && countryCode.length() == 2) {
                return countryCode;
            }
            countryCode = ctx.getResources().getConfiguration().locale.getCountry();
            Log.d("MAIN", "Locale: '" + countryCode + "'");
            if (countryCode != null && countryCode.length() == 2) {
                return countryCode;
            }
        }
        return null;
    }

    private void setupViewPager(ViewPager viewPager) {
        String countryCode = getCountryCode();

        fragments[IDX_LOCAL] = new FragmentLocalStations();
        fragments[IDX_TOP_CLICK] = new FragmentTopClick();
        fragments[IDX_TOP_VOTE] = new FragmentTopVote();
        fragments[IDX_CHANGED_LATELY] = new FragmentRecentlyChanged();
        fragments[IDX_MULTI_SEARCH] = new net.programmierecke.radiodroid2.station.FragmentMultiSearch();

        for (int i=0;i<fragments.length;i++) {
            Bundle bundle = new Bundle();
            fragments[i].setArguments(bundle);
        }

        FragmentManager m = getChildFragmentManager();
        viewPagerAdapter = new ViewPagerAdapter(m);
        if (countryCode != null){
            viewPagerAdapter.addFragment(fragments[IDX_LOCAL], R.string.action_local);
        }
        viewPagerAdapter.addFragment(fragments[IDX_TOP_CLICK], R.string.action_top_click);
        viewPagerAdapter.addFragment(fragments[IDX_TOP_VOTE], R.string.action_top_vote);
        viewPagerAdapter.addFragment(fragments[IDX_CHANGED_LATELY], R.string.action_changed_lately);
        viewPagerAdapter.addFragment(fragments[IDX_MULTI_SEARCH], R.string.action_search);
        viewPager.setAdapter(viewPagerAdapter);
    }

    public void search(StationsFilter.SearchStyle searchStyle, final String query) {
        Log.d("TABS","Search = "+ query + " searchStyle="+searchStyle);
        if (viewPager != null && viewPagerAdapter != null) {
            Log.d("TABS","a Search = "+ query);
            int searchIndex = viewPagerAdapter.mFragmentList.size() - 1;
            viewPager.setCurrentItem(searchIndex, false);
        } else {
            Log.d("TABS","b Search = "+ query);
            queuedSearchQuery = query;
            queuedSearchStyle = searchStyle;
        }
    }

    @Override
    public void Refresh() {
        Fragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof IFragmentRefreshable) {
            ((IFragmentRefreshable) fragment).Refresh();
        }
    }

    class ViewPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragmentList = new ArrayList<>();
        private final List<Integer> mFragmentTitleList = new ArrayList<Integer>();

        public ViewPagerAdapter(FragmentManager manager) {
            super(manager);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragmentList.get(position);
        }

        @Override
        public int getCount() {
            return mFragmentList.size();
        }

        public void addFragment(Fragment fragment, int title) {
            mFragmentList.add(fragment);
            mFragmentTitleList.add(title);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            Resources res = getResources();
            return res.getString(mFragmentTitleList.get(position));
        }
    }
}
