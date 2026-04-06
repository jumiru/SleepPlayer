package com.example.sleepplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView-Adapter für die Track-Liste.
 */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.TrackViewHolder> {

    private List<TrackSelector.TrackInfo> tracks = new ArrayList<>();
    private OnTrackClickListener listener;

    public interface OnTrackClickListener {
        void onTrackClick(TrackSelector.TrackInfo track);
    }

    public void setOnTrackClickListener(OnTrackClickListener listener) {
        this.listener = listener;
    }

    public void setTracks(List<TrackSelector.TrackInfo> tracks) {
        this.tracks = tracks != null ? tracks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TrackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new TrackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackViewHolder holder, int position) {
        TrackSelector.TrackInfo track = tracks.get(position);
        holder.tvTitle.setText(track.title);
        holder.tvArtist.setText(track.artist);
        holder.tvDuration.setText(track.getFormattedDuration());

        // Album-Art laden (Glide kümmert sich um Caching und Placeholder)
        Glide.with(holder.itemView.getContext())
                .load(track.getAlbumArtUri())
                .placeholder(R.drawable.ic_album_placeholder)
                .error(R.drawable.ic_album_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .centerCrop()
                .into(holder.ivAlbumArt);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTrackClick(track);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    static class TrackViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivAlbumArt;
        final TextView tvTitle;
        final TextView tvArtist;
        final TextView tvDuration;

        TrackViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAlbumArt = itemView.findViewById(R.id.ivItemAlbumArt);
            tvTitle = itemView.findViewById(R.id.tvItemTitle);
            tvArtist = itemView.findViewById(R.id.tvItemArtist);
            tvDuration = itemView.findViewById(R.id.tvItemDuration);
        }
    }
}
