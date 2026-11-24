package fi.alavesa.phone.media;

import fi.alavesa.phone.storage.Database;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;

/**
 * The Photos app's library: every photo and video a player takes (and every one sent to them) until they delete it.
 * Rows live in the {@code gallery} table, pictures in {@code plugins/Phone/gallery/}. Futures complete off the main
 * thread; callers hop back with {@code Tasks.onMain}.
 */
public final class Gallery {

    private final Database db;
    private final MediaStore store;
    private volatile int limit;

    public Gallery(Database db, MediaStore store, int limit) {
        this.db = db;
        this.store = store;
        this.limit = limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public MediaStore store() {
        return store;
    }

    /** Saves a new picture; the new id, or -1 when the owner's library is full. */
    public CompletableFuture<Long> add(UUID owner, MediaStore.Media media, long takenAt, Location where, String fromName) {
        String world = where == null || where.getWorld() == null ? null : where.getWorld().getName();
        int x = where == null ? 0 : where.getBlockX(), y = where == null ? 0 : where.getBlockY(), z = where == null ? 0 : where.getBlockZ();
        return db.insertGallery(owner, takenAt, media.frames().size(), media.fps(), world, x, y, z, fromName, limit)
                .thenCompose(id -> id < 0 ? CompletableFuture.completedFuture(id) : store.write(id, media).thenApply(v -> id));
    }

    /** Newest first. */
    public CompletableFuture<List<Database.GalleryItem>> list(UUID owner) {
        return db.gallery(owner);
    }

    public CompletableFuture<Database.GalleryItem> item(long id) {
        return db.galleryItem(id);
    }

    public CompletableFuture<MediaStore.Media> media(long id) {
        return store.read(id);
    }

    public CompletableFuture<Boolean> delete(long id, UUID owner) {
        return db.deleteGallery(id, owner).thenCompose(gone -> gone ? store.delete(id).thenApply(v -> true)
                : CompletableFuture.completedFuture(false));
    }

    /**
     * Puts a copy of picture {@code id} into {@code to}'s library, marked as sent by {@code fromName}. The new id, -1
     * when their library is full, -2 when the picture is gone.
     */
    public CompletableFuture<Long> sendCopy(long id, UUID to, String fromName) {
        return db.galleryItem(id).thenCompose(item -> {
            if (item == null) return CompletableFuture.completedFuture(-2L);
            return db.insertGallery(to, item.takenAt(), item.frames(), item.fps(), item.world(), item.x(), item.y(), item.z(), fromName, limit)
                    .thenCompose(copy -> copy < 0 ? CompletableFuture.completedFuture(copy)
                            : store.copyTo(id, store, copy).thenApply(ok -> copy));
        });
    }
}
