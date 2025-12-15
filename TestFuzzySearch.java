package com.example;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class TestFuzzySearch {
    public static void main(String[] args) {
        // Test Chinese search
        String query = "中文";
        String text1 = "这是中文内容";
        String text2 = "This is English content";
        
        int ratio1 = FuzzySearch.partialRatio(query, text1);
        int ratio2 = FuzzySearch.partialRatio("English", text2);
        
        System.out.println("Chinese match ratio: " + ratio1);
        System.out.println("English match ratio: " + ratio2);
    }
}