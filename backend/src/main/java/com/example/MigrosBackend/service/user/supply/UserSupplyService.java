package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.OrderStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserSupplyService {
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final UserCatalogReadService catalogReadService;
    private final UserOrderHistoryReadService orderHistoryReadService;
    private final OrderStockRestocker orderStockRestocker;

    @Autowired
    public UserSupplyService(
            UserEntityRepository userEntityRepository,
            TokenService tokenService,
            OrderEntityRepository orderEntityRepository,
            OrderGroupEntityRepository orderGroupEntityRepository,
            UserCatalogReadService catalogReadService,
            UserOrderHistoryReadService orderHistoryReadService,
            OrderStockRestocker orderStockRestocker
    ) {
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.catalogReadService = catalogReadService;
        this.orderHistoryReadService = orderHistoryReadService;
        this.orderStockRestocker = orderStockRestocker;
    }

    public List<String> getAllCategoryNames() {
        return catalogReadService.getAllCategoryNames();
    }

    public List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) {
        return catalogReadService.getProductsFromCategory(categoryId, page, itemRange);
    }

    public List<ProductPreviewDto> getAllProducts(int page, int itemRange) {
        return catalogReadService.getAllProducts(page, itemRange);
    }

    public int getAllProductCounts() {
        return catalogReadService.getAllProductCounts();
    }

    public List<String> getProductImageNames(Long itemId) {
        return catalogReadService.getProductImageNames(itemId);
    }

    public Resource getProductImage(Long itemId) {
        return catalogReadService.getProductImage(itemId);
    }

    public int getProductCountsFromCategory(Long categoryId) {
        return catalogReadService.getProductCountsFromCategory(categoryId);
    }

    public List<SubCategoryDto> getSubCategories(Long categoryId) {
        return catalogReadService.getSubCategories(categoryId);
    }

    public List<ProductPreviewDto> getProductsFromSubcategory(String subcategoryName, int page, int productRange) {
        return catalogReadService.getProductsFromSubcategory(subcategoryName, page, productRange);
    }

    public int getProductCountsFromSubcategory(String subcategoryName) {
        return catalogReadService.getProductCountsFromSubcategory(subcategoryName);
    }

    public ProductDetailDto getProductData(Long productId) {
        return catalogReadService.getProductData(productId);
    }

    public List<Long> getAllOrderIds(String token) {
        return orderHistoryReadService.getAllOrderIds(token);
    }

    public String getOrderStatusByOrderId(Long orderId, String token) {
        return orderHistoryReadService.getOrderStatusByOrderId(orderId, token);
    }

    /**
     * Cancels one of the caller's orders and returns its reserved units to
     * live stock.
     *
     * <p>The order row is locked for update <em>before</em> its status is read.
     * That lock is the whole correctness argument: it serializes this
     * cancellation against a concurrent administrator status change and against
     * a second cancellation of the same order, so exactly one of them can
     * observe {@code Pending} and restock. Without it, a cancellation racing an
     * admin status change could return stock for an order the admin had already
     * shipped, or restock the same order twice.
     *
     * <p>The ownership check and the lock are one statement, so ownership cannot
     * change between them. Status strings are unchanged, preserving the existing
     * API contract.
     *
     * <p>The legacy fallback only considers order lines that have no order
     * group. Group ids and line ids are independent sequences, so an
     * unrestricted "the line with this id" lookup could cancel - and restock -
     * a line belonging to a completely different order.
     */
    @Transactional
    public void cancelOrder(Long orderId, String token) {
        UserEntity user = getValidatedUserFromToken(token);

        OrderGroupEntity orderGroup = orderGroupEntityRepository
                .findByIdAndUserIdForUpdate(orderId, user.getId())
                .orElse(null);
        if (orderGroup != null) {
            if (!OrderStatus.isPending(orderGroup.getStatus())) {
                throw new GeneralException("Only pending orders can be canceled.");
            }

            List<OrderEntity> orderItems = new ArrayList<>(orderGroup.getOrderItems());
            orderStockRestocker.restore(orderItems);

            orderEntityRepository.deleteAll(orderItems);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndUserIdAndOrderGroupIsNullForUpdate(orderId, user.getId())
                .orElseThrow(() -> new GeneralException("Order not found"));

        if (!OrderStatus.isPending(legacyOrder.getStatus())) {
            throw new GeneralException("Only pending orders can be canceled.");
        }

        orderStockRestocker.restore(legacyOrder.getItemId(),
                legacyOrder.getCount() == null ? 0 : legacyOrder.getCount());
        orderEntityRepository.delete(legacyOrder);
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        return catalogReadService.getProductDescription(productId);
    }

    public List<UserOrderDetailDto> getUserOrderDetails(String token) {
        return orderHistoryReadService.getUserOrderDetails(token);
    }

    public List<UserOrderGroupDto> getUserOrderGroups(String token) {
        return orderHistoryReadService.getUserOrderGroups(token);
    }

    private UserEntity getValidatedUserFromToken(String token) {
        String userName = tokenService.validateAndExtractUser(token);

        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null) {
            throw new UserNotFoundException(userName);
        }

        return user;
    }
}
