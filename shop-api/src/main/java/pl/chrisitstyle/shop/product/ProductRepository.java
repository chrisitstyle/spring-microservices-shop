package pl.chrisitstyle.shop.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.chrisitstyle.shop.product.domain.Product;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
}
