package service.transfer;

/** A transfer command paired with the file's size, for size-based batching. */
public final class SizedCommand {
    public final TransferCommand command;
    public final long size;

    public SizedCommand(TransferCommand command, long size) {
        this.command = command;
        this.size = size;
    }
}
