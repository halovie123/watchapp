package com.example.watchapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class OnboardingFragment extends Fragment {
    private static final String ARG_POSITION = "position";

    public static OnboardingFragment newInstance(int position) {
        OnboardingFragment fragment = new OnboardingFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POSITION, position);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_onboarding, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView imageView = view.findViewById(R.id.imageOnboarding);
        TextView title = view.findViewById(R.id.titleOnboarding);
        TextView description = view.findViewById(R.id.descriptionOnboarding);

        int position = getArguments() != null ? getArguments().getInt(ARG_POSITION) : 0;

        switch (position) {
            case 0:
                imageView.setImageResource(R.drawable.watch_image);
                title.setText(R.string.onboarding_title);
                description.setText(R.string.onboarding_desc);
                break;
            case 1:
                imageView.setImageResource(R.drawable._watch);
                title.setText(R.string.onboarding_title1);
                description.setText(R.string.onboarding_desc1);
                break;
            case 2:
                imageView.setImageResource(R.drawable.heartrate);
                title.setText(R.string.onboarding_title2);
                description.setText(R.string.onboarding_desc2);
                break;
        }
    }
}