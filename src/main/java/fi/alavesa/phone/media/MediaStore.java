package fi.alavesa.phone.media;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

/**
 * Photo and video files of the Photos app and of Minestagram posts: {@code <dir>/<id>.png} for a photo,
 * {@code <dir>/<id>.bin} (gzip, the same format as printed videos) for a video. All file work happens on one
 * background thread; the last few pictures read stay in memory, because the same picture is usually shown several
 * times in a row (paging back and forth, liking, commenting).
 */
public final class MediaStore {

    /** A photo (one frame) or a video. Frames are 128x128 0xRRGGBB, row by row; never modified after creation. */
    public record Media(List<int[]> frames, int fps) {
        public boolean video() {
            return frames.size() > 1;
        }

        public int[] poster() {
            return frames.getFirst();
        }

        public double seconds() {
            return frames.size() / (double) fps;
        }
    }

    public static final int SIZE = 128;
    private static final int VIDEO_MAGIC = 0x50485631;      // "PHV1", as in camera/PhotoStore
    private static final int CACHE = 24;

    private final Logger log;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phone-media");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Media> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Media> eldest) {
            return size() > CACHE;
        }
    };

    public MediaStore(Logger log, File dir) {
        this.log = log;
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) log.warning("Could not create " + dir);
    }

    public void shutdown() {
        io.shutdown();
    }

    private File file(long id, boolean video) {
        return new File(dir, id + (video ? ".bin" : ".png"));
    }

    private synchronized Media cached(long id) {
        return cache.get(id);
    }

    private synchronized void remember(long id, Media media) {
        cache.put(id, media);
    }

    private synchronized void forget(long id) {
        cache.remove(id);
    }

    public CompletableFuture<Void> write(long id, Media media) {
        remember(id, media);
        return CompletableFuture.runAsync(() -> {
            try {
                writeFile(file(id, media.video()), media);
            } catch (IOException e) {
                log.log(Level.SEVERE, "Could not save picture " + id + " in " + dir, e);
            }
        }, io);
    }

    /** The picture, or null when its file is gone or unreadable. */
    public CompletableFuture<Media> read(long id) {
        Media hit = cached(id);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        return CompletableFuture.supplyAsync(() -> {
            Media again = cached(id);
            if (again != null) return again;
            try {
                File video = file(id, true);
                Media m = video.isFile() ? readVideo(video) : readPhoto(file(id, false));
                if (m != null) remember(id, m);
                return m;
            } catch (IOException e) {
                log.warning("Picture " + id + " in " + dir.getName() + " could not be read (" + e.getMessage() + ")");
                return null;
            }
        }, io);
    }

    public CompletableFuture<Void> delete(long id) {
        forget(id);
        return CompletableFuture.runAsync(() -> {
            file(id, true).delete();
            file(id, false).delete();
        }, io);
    }

    /** Copies picture {@code id} of this store to {@code target} under {@code targetId}. */
    public CompletableFuture<Boolean> copyTo(long id, MediaStore target, long targetId) {
        return CompletableFuture.supplyAsync(() -> {
            for (boolean video : new boolean[] {true, false}) {
                File from = file(id, video);
                if (!from.isFile()) continue;
                try {
                    Files.copy(from.toPath(), target.file(targetId, video).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } catch (IOException e) {
                    log.log(Level.WARNING, "Could not copy picture " + id, e);
                    return false;
                }
            }
            return false;
        }, io);
    }

    // ---- file formats -----------------------------------------------------------------------------------------------

    static void writeFile(File file, Media media) throws IOException {
        if (!media.video()) {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, SIZE, SIZE, media.poster(), 0, SIZE);
            ImageIO.write(image, "png", file);
            return;
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(VIDEO_MAGIC);
            out.writeInt(media.frames().size());
            out.writeInt(media.fps());
            byte[] row = new byte[SIZE * SIZE * 3];
            for (int[] frame : media.frames()) {
                for (int i = 0; i < frame.length; i++) {
                    row[i * 3] = (byte) (frame[i] >> 16);
                    row[i * 3 + 1] = (byte) (frame[i] >> 8);
                    row[i * 3 + 2] = (byte) frame[i];
                }
                out.write(row);
            }
        }
    }

    static Media readPhoto(File file) throws IOException {
        if (!file.isFile()) return null;
        BufferedImage image = ImageIO.read(file);
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) throw new IOException("not a " + SIZE + "x" + SIZE + " image");
        int[] pixels = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < pixels.length; i++) pixels[i] &= 0xFFFFFF;
        return new Media(List.of(pixels), 1);
    }

    static Media readVideo(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != VIDEO_MAGIC) throw new IOException("not a phone video");
            int count = in.readInt();
            int rate = in.readInt();
            if (count < 1 || count > 600 || rate < 1 || rate > 20) throw new IOException("bad header");
            int[][] out = new int[count][SIZE * SIZE];
            byte[] row = new byte[SIZE * SIZE * 3];
            for (int f = 0; f < count; f++) {
                in.readFully(row);
                for (int i = 0; i < SIZE * SIZE; i++) {
                    out[f][i] = ((row[i * 3] & 255) << 16) | ((row[i * 3 + 1] & 255) << 8) | (row[i * 3 + 2] & 255);
                }
            }
            return new Media(List.of(out), rate);
        }
    }
}
