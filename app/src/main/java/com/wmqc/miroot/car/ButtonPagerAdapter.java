package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class ButtonPagerAdapter extends FragmentStateAdapter {
    private static final String TAG = "ButtonPagerAdapter";
    private String[] buttonFunctions;
    
    public ButtonPagerAdapter(@NonNull FragmentActivity fragmentActivity, String[] buttonFunctions) {
        super(fragmentActivity);
        this.buttonFunctions = buttonFunctions;
    }
    
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        LogHelper.d(TAG, "📄 创建Fragment，位置=" + position + "，buttonFunctions=" + (buttonFunctions != null ? "非null，长度=" + buttonFunctions.length : "null"));
        return ButtonPageFragment.newInstance(position, buttonFunctions);
    }
    
    @Override
    public int getItemCount() {
        if (buttonFunctions == null) {
            return 0;
        }
        int buttonCount = 0;
        for (String func : buttonFunctions) {
            if (func != null && !func.isEmpty()) {
                buttonCount++;
            }
        }
        int pages = (buttonCount + 3) / 4; // 向上取整
        if (pages < 1) {
            pages = 1;
        }
        if (pages > 2) {
            pages = 2; // 最多2页
        }
        LogHelper.d(TAG, "📊 Adapter getItemCount: 按钮数=" + buttonCount + "，页数=" + pages);
        return pages;
    }
}
