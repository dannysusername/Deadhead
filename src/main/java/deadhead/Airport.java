package deadhead;

/** An airport, identified by its code (e.g. "ADS", "DAL", "AUS"). */
public record Airport(String code) {
    @Override
    public String toString() {
        return code;
    }
}
