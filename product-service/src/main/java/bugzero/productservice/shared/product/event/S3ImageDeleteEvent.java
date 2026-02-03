package bugzero.productservice.shared.product.event;

import java.util.List;

public record S3ImageDeleteEvent(List<String> paths) {
}
