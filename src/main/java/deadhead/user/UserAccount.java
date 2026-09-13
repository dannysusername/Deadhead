package deadhead.user;

/** One signed-up pilot. The hash never leaves the persistence layer. */
public record UserAccount(long id, String email, String passwordHash, String createdAt) {}
