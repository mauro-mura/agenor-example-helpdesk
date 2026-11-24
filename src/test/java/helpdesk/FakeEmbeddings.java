package helpdesk;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import dev.agenor.core.knowledge.EmbeddingProvider;

/**
 * A deterministic embedding for tests: each word increments one of 256 dimensions, then the vector
 * is normalised. Two texts with words in common are close, with no network. Agenor 0.42 has a
 * scripted {@code LLMProvider} for tests but no fake {@code EmbeddingProvider}.
 */
class FakeEmbeddings implements EmbeddingProvider {

    static final int DIMENSIONS = 256;

    private final String model;

    /** When set, every embedding fails with this error. */
    volatile RuntimeException failure;

    FakeEmbeddings() {
        this("fake");
    }

    FakeEmbeddings(String model) {
        this.model = model;
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() > 2) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
            }
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        double divisor = norm == 0 ? 1 : Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= (float) divisor;
        }
        // pgvector rejects the zero vector for cosine distance: never all zeros
        vector[0] += 1e-3f;
        return CompletableFuture.completedFuture(vector);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelId() {
        return model;
    }
}
