package ph.opendtv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ph.opendtv.R;
import ph.opendtv.ts.ServiceInfo;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(ServiceInfo service);
        void onFavoriteToggle(ServiceInfo service, boolean isFavorite);
    }

    /**
     * Which rows are starred. The owner keys favorites per list entry (frequency + service ID), because the same
     * service ID can be two different channels on two multiplexes.
     */
    public interface Favorites {
        boolean contains(ServiceInfo service);

        /** Flips the star; returns true if the channel is now a favorite. */
        boolean toggle(ServiceInfo service);
    }

    private final Context context;
    private final List<ServiceInfo> channels;
    private final Favorites favorites;
    private final OnChannelClickListener listener;

    public ChannelAdapter(Context context, List<ServiceInfo> channels, Favorites favorites, OnChannelClickListener listener) {
        this.context = context;
        this.channels = channels;
        this.favorites = favorites;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServiceInfo channel = channels.get(position);
        holder.tvVirtual.setText(channel.virtualNumber);
        holder.tvChannelName.setText(channel.name.isEmpty() ? "Service " + channel.serviceId : channel.name);
        holder.tvNetworkName.setText(channel.provider.isEmpty() ? "ISDB-T Broadcast" : channel.provider);

        Drawable logo = LogoHelper.getLogo(context, channel.name, channel.provider);
        if (logo != null) {
            holder.ivLogo.setImageDrawable(logo);
        } else {
            holder.ivLogo.setImageResource(android.R.drawable.ic_menu_slideshow);
        }

        boolean isFav = favorites.contains(channel);
        holder.ivFavorite.setImageResource(isFav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(channel);
        });

        holder.itemView.setOnLongClickListener(v -> {
            boolean newState = favorites.toggle(channel);
            notifyItemChanged(position);
            if (listener != null) listener.onFavoriteToggle(channel, newState);
            return true;
        });

        holder.ivFavorite.setOnClickListener(v -> {
            boolean newState = favorites.toggle(channel);
            notifyItemChanged(position);
            if (listener != null) listener.onFavoriteToggle(channel, newState);
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivLogo;
        TextView tvVirtual;
        TextView tvChannelName;
        TextView tvNetworkName;
        ImageView ivFavorite;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogo = itemView.findViewById(R.id.iv_logo);
            tvVirtual = itemView.findViewById(R.id.tv_virtual);
            tvChannelName = itemView.findViewById(R.id.tv_channel_name);
            tvNetworkName = itemView.findViewById(R.id.tv_network_name);
            ivFavorite = itemView.findViewById(R.id.iv_favorite);
        }
    }
}
