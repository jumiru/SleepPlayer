package com.example.sleepplayer;

import android.graphics.Color;
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
 *
 * Unterstützt:
 *  - Klick → Track abspielen
 *  - Longpress → Track als Normalisierungs-Referenz markieren
 *  - Badge-Anzeige für Referenz-Track (⭐) und normalisierte Tracks (Gain in dB)
 */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.TrackViewHolder> {

    private List<TrackSelector.TrackInfo> tracks = new ArrayList<>();
    private OnTrackClickListener listener;
    private OnTrackLongClickListener longClickListener;
    private NormalizationStore normStore;

    public interface OnTrackClickListener {
        void onTrackClick(TrackSelector.TrackInfo track);
    }

    public interface OnTrackLongClickListener {
        void onTrackLongClick(TrackSelector.TrackInfo track);
    }

    public void setOnTrackClickListener(OnTrackClickListener listener) {
        this.listener = listener;
    }

    public void setOnTrackLongClickListener(OnTrackLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setNormalizationStore(NormalizationStore store) {
        this.normStore = store;
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

        // Normalisierungs-Badge anzeigen
        if (normStore != null) {
            String uri = track.uri.toString();
            String refUri = normStore.getReferenceTrackUri();
            if (uri.equals(refUri)) {
                holder.tvNormBadge.setVisibility(View.VISIBLE);
                holder.tvNormBadge.setText("⭐ Ref");
                holder.tvNormBadge.setTextColor(Color.parseColor("#FFD700"));
            } else if (normStore.hasGain(uri)) {
                float gain = normStore.getGain(uri);
                float gainDb = (float)(20.0 * Math.log10(gain));
                String label;
                if (Math.abs(gainDb) < 0.5f) {
                    label = "±0 dB";
                } else {
                    label = String.format("%+.1f dB", gainDb);
                }
                holder.tvNormBadge.setVisibility(View.VISIBLE);
                holder.tvNormBadge.setText(label);
                if (gainDb > 0.5f) {
                    holder.tvNormBadge.setTextColor(Color.parseColor("#4CAF50"));
                } else if (gainDb < -0.5f) {
                    holder.tvNormBadge.setTextColor(Color.parseColor("#FF5252"));
                } else {
                    holder.tvNormBadge.setTextColor(Color.parseColor("#99FFFFFF"));
                }
            } else {
                holder.tvNormBadge.setVisibility(View.GONE);
            }
        } else {
            holder.tvNormBadge.setVisibility(View.GONE);
        }

        // Album-Art laden
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

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onTrackLongClick(track);
                return true;
            }
            return false;
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
        final TextView tvNormBadge;

        TrackViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAlbumArt  = itemView.findViewById(R.id.ivItemAlbumArt);
            tvTitle     = itemView.findViewById(R.id.tvItemTitle);
            tvArtist    = itemView.findViewById(R.id.tvItemArtist);
            tvDuration  = itemView.findViewById(R.id.tvItemDuration);
            tvNormBadge = itemView.findViewById(R.id.tvItemNormBadge);
        }
    }
}
