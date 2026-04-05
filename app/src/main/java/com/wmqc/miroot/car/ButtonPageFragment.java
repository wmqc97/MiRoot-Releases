package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.fragment.app.Fragment;

public class ButtonPageFragment extends Fragment {
    private static final String TAG = "ButtonPageFragment";
    private static final String ARG_PAGE_INDEX = "page_index";
    private static final String ARG_BUTTON_FUNCTIONS = "button_functions";
    
    private int pageIndex;
    private String[] buttonFunctions;
    private Button[] buttons = new Button[4];
    private RearScreenCarControlActivity activity;
    
    public static ButtonPageFragment newInstance(int pageIndex, String[] allButtonFunctions) {
        ButtonPageFragment fragment = new ButtonPageFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PAGE_INDEX, pageIndex);
        args.putStringArray(ARG_BUTTON_FUNCTIONS, allButtonFunctions);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            pageIndex = getArguments().getInt(ARG_PAGE_INDEX);
            buttonFunctions = getArguments().getStringArray(ARG_BUTTON_FUNCTIONS);
            LogHelper.d(TAG, "📄 Fragment onCreate: pageIndex=" + pageIndex + ", buttonFunctions=" + (buttonFunctions != null ? "非null，长度=" + buttonFunctions.length : "null"));
        } else {
            LogHelper.e(TAG, "❌ Fragment onCreate: getArguments()为null");
        }
    }
    
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof RearScreenCarControlActivity) {
            this.activity = (RearScreenCarControlActivity) context;
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout rootLayout = new LinearLayout(getContext());
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setGravity(Gravity.CENTER);
        rootLayout.setPadding(0, 0, 0, 20);
        
        if (buttonFunctions != null && activity != null) {
            LogHelper.d(TAG, "📄 创建第" + pageIndex + "页，buttonFunctions长度=" + buttonFunctions.length);
            for (int i = 0; i < 4; i++) {
                int buttonIndex = pageIndex * 4 + i;
                LogHelper.d(TAG, "🔘 检查按钮索引" + buttonIndex + " (页面" + pageIndex + "的第" + i + "个按钮)");
                if (buttonIndex < buttonFunctions.length && buttonFunctions[buttonIndex] != null && !buttonFunctions[buttonIndex].isEmpty()) {
                    String buttonText = buttonFunctions[buttonIndex];
                    LogHelper.d(TAG, "✅ 创建按钮: " + buttonText + " (索引" + buttonIndex + ")");
                    Button button = activity.createControlButton(buttonText);
                    if (button != null) {
                        buttons[i] = button;
                        activity.controlButtons[buttonIndex] = button;
                        rootLayout.addView(button);
                        LogHelper.d(TAG, "✅ 按钮已添加到布局: " + buttonText);
                        
                        // 为按钮设置长按监听（在按钮创建后立即设置）
                        activity.setupButtonLongPress(button, buttonIndex);
                    } else {
                        LogHelper.w(TAG, "⚠️ createControlButton返回null: " + buttonText);
                    }
                } else {
                    if (buttonIndex >= buttonFunctions.length) {
                        LogHelper.d(TAG, "⏭️ 按钮索引" + buttonIndex + "超出数组长度" + buttonFunctions.length);
                    } else if (buttonFunctions[buttonIndex] == null) {
                        LogHelper.d(TAG, "⏭️ 按钮索引" + buttonIndex + "为null");
                    } else {
                        LogHelper.d(TAG, "⏭️ 按钮索引" + buttonIndex + "为空字符串");
                    }
                }
            }
        } else {
            if (buttonFunctions == null) {
                LogHelper.e(TAG, "❌ buttonFunctions为null");
            }
            if (activity == null) {
                LogHelper.e(TAG, "❌ activity为null");
            }
        }
        
        LogHelper.d(TAG, "📄 第" + pageIndex + "页创建完成，子视图数量=" + rootLayout.getChildCount());
        return rootLayout;
    }
    
    public Button[] getButtons() {
        return buttons;
    }
}
