package app.splashskip.android;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class PauseTile extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateState(new SettingsStore(this));
    }

    @Override
    public void onClick() {
        SettingsStore settings = new SettingsStore(this);
        settings.setEnabled(!settings.enabled());
        updateState(settings);
    }

    private void updateState(SettingsStore settings) {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = settings.enabled();
        tile.setLabel(getString(R.string.tile_name));
        tile.setState(enabled && SkipService.connected() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(!enabled ? "已暂停" : SkipService.connected() ? "运行中" : "服务未连接");
        }
        tile.updateTile();
    }
}
