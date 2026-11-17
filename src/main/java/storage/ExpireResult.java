package storage;

public record ExpireResult(
        boolean success,
        long deadLineMills
) {
}
