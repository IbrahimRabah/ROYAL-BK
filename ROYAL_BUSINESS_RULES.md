# ROYAL_BUSINESS_RULES — المرجع الموثَّق لقواعد العمل

> **كيف يُقرأ هذا الملف:** مرجع لقواعد العمل كما يطبّقها **هذا الكود** (مشروع ROYAL، فرع من VELORA)، مبني على قراءة الكود والسكربتات فقط؛ آخر تحديث بعد جولات إصلاح (الأمان، 401/403، سلاج التصنيف، إعدادات الضريبة، استبدال الفاتورة والتسوية، `cancellable`)؛ الاختبارات الآلية شُغّلت على `royal_test` ونجحت كلها، وقاعدة البيانات الحية `royal` لم تُفحص ولم يُطبَّق عليها V19. كل قسم فرعي ينتهي بسطر `المصدر:` بمسارات وأرقام أسطر (قد تتغير بتعديل الكود). ما لم أستطع تأكيده في الكود مجموع في القسم 8 ولا يجوز اعتماده كحقيقة.
> أسماء الحقول والمسارات والحالات والأكواد محفوظة بالإنجليزية كما في الكود. أمثلة المسارات بدون البادئة `/api/v1`.

## 1. ما هو النشاط التجاري

> **تنبيه منهجي:** هذا المشروع فرع من مشروع سابق اسمه VELORA، وما زال الكود وأسماء الحزم (`com.velora.api`) وبعض الأمثلة والتعليقات تحمل اسمه. الوصف أدناه مستخرج من نموذج البيانات والمنطق الفعلي، لا من أسماء الملفات.

### 1.1 ما الذي يُباع

- **وحدة البيع هي `product_variant` وليست `product`.** السلة (`cart_item`) وسطر الطلب (`order_item`) وحجز المخزون وحركة المخزون كلها تشير إلى الـ variant. الإضافة للسلة تُرسل `variantId` (`AddToCartRequest`) ولا تقبل `productId`.
- المنتج (`product`) هو الغلاف: تصنيف (`category`) وعلامة تجارية اختيارية (`brand`) وترجمات `ar`/`en` ومواصفات إعلامية. المتغيّر (`product_variant`) هو الذي يحمل `sku` والسعر `price` ومعدل الضريبة `taxRate` والوزن `weightGrams` وصف المخزون (`inventory`). منتج بمتغيّر واحد أمر عادي.
- **السعر شامل الضريبة (tax-inclusive)** — السعر المعروض هو ما يدفعه العميل. الضريبة تُستخرج ولا تُضاف (راجع 2.3).
- كل منتج له `fulfillmentType` (الافتراضي `READY_MADE`)، وله `shippingSizeClass` بحجم طرد `SMALL | MEDIUM | LARGE` (إلزامي لمنتجات `READY_MADE`)، وله اختياريًا `requiresAssembly` مع `assemblyFee` (رسوم تركيب **لكل قطعة**، شاملة الضريبة).
- المنتجات والمتغيّرات تُؤرشف ولا تُحذف؛ والـ `sku` فريد على الجدول كله بما فيه المؤرشف (`uq_var_sku`)، لكن **تعديل متغيّر موجود يسمح بتغيير `sku`** (`POST .../variants` مع `id`) فيتحرر القديم ويمكن إعادة استخدامه؛ فقاعدة «لا يُعاد استخدام SKU» لا تحمي من إعادة التسمية.
- **ما هي فئة المنتجات تحديدًا؟ الكود لا يحسم ذلك.** تعليقات الكود تذكر "furniture" (أثاث) صراحةً في ثلاثة مواضع (جدولة التسليم قبل خروج البضاعة من المخزن)، ونموذج البيانات يدعم الأبعاد بالسنتيمتر وأحجام الطرد LARGE ورسوم التركيب ومعرض أعمال منجزة (`portfolio`)؛ بينما ملف `CLAUDE.md` الموروث يذكر ساعات ومحافظ وعطورًا. لا توجد بيانات كتالوج في ملفات الـ seed. انظر القسم 8.

المصدر: `src/main/java/com/velora/api/catalog/domain/Product.java:68,85,160-162,164-166` · `src/main/java/com/velora/api/cart/service/CartService.java:83-123,305-316` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:416-421` · `src/main/java/com/velora/api/order/domain/FulfillmentStatus.java:22` · `src/main/java/com/velora/api/order/domain/CustomerOrder.java:198` · `src/main/java/com/velora/api/order/service/CheckoutService.java:244` · `src/main/resources/db/baseline/baseline_through_V14.sql:1063-1065`

### 1.2 كيف يتحول الطلب إلى مال

1. العميل (مسجّل أو ضيف) يملأ سلة، ثم يستدعي `POST /api/v1/orders`. الخادم يعيد حساب الأسعار من أسعار المتغيّرات **الحالية**، يحجز المخزون، وينسخ الأسماء والأسعار والعنوان على الطلب، ويُنشئ الطلب بحالة `PENDING` للتنفيذ و`PENDING` للدفع — كل ذلك في معاملة واحدة (`@Transactional` على `CheckoutService.placeOrder`).
2. المبلغ المطلوب `grandTotal` = `subtotalGross` − `discountTotal` + `shippingCost` + `codFee` + `assemblyTotal` (قرّب إلى 4 خانات عشرية). الخصم دائمًا **صفر اليوم**: حقل الخصم موجود ومخصَّص على الأسطر عبر `MoneyUtils.allocate` لكن قيمته ثابتة `MoneyUtils.ZERO` مع تعليق `TODO(promotion module)`. لا يوجد كوبونات في الكود.
3. **الضريبة** تُستخرج من السعر الشامل لكل سطر (وكذلك من رسوم التركيب) ثم تُجمع. **الشحن ورسوم COD لا تدخل في الضريبة** حاليًا: `net = grandTotal − tax`. التعليق في الكود يطلب تأكيد محاسب (`TODO(accountant)`) لهذين البندين ولرسوم التركيب.
4. الموظف يؤكد الطلب هاتفيًا (`CONFIRMED`)، ثم يُجهَّز ويُشحن. عند `SHIPPED` يتحوّل حجز المخزون إلى خصم فعلي من `qty_on_hand`.
5. عند `DELIVERED` تصدر **فاتورة** تلقائيًا في نفس المعاملة (لقطة كاملة، ترقيم متسلسل بلا فجوات). الدفع يبقى `PENDING` — هذا الوضع الطبيعي للدفع عند الاستلام.
6. المال يتحقق عمليًا عندما يسلّم شركة الشحن النقد: الأدمن يسجّل **تسوية (`cod_remittance`)** لمجموعة طلبات `DELIVERED`+`COD`+`PENDING`، فيصبح `paymentStatus = PAID` لكل طلب فيها (راجع 3.5). لوحة التحكم تحسب "مبلغ لدى الكوريير" من الطلبات المسلَّمة غير المسوّاة.

المصدر: `src/main/java/com/velora/api/order/service/CheckoutService.java:126-236,310-366,324,341,362` · `src/main/java/com/velora/api/order/service/OrderService.java:131-208` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:100-102` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:104-195`

### 1.3 طرق الدفع وحالات الدفع

- `PaymentMethod` يعرّف: `COD`, `CARD`, `WALLET`, `FAWRY`. **المتاح فعليًا `COD` فقط.** `CheckoutService.resolvePaymentMethod` يعيد `COD` عند غياب الحقل، ويرمي `PAYMENT_METHOD_UNAVAILABLE` (HTTP 409) لأي قيمة أخرى — سواء كانت قيمة معروفة (`CARD`...) أو نصًا مجهولًا.
- لا يوجد أي مُعالِج دفع (payment processor/gateway) في الكود، ولا ربط بـ `payment_transaction` (جدول موجود في المخطط بلا كيان Java).
- `PaymentStatus`: `PENDING, AUTHORIZED, PAID, PARTIALLY_REFUNDED, REFUNDED, FAILED, EXPIRED`. المستخدم فعليًا: `PENDING` (عند الإنشاء) و`PAID` (عبر التسوية أو عبر PATCH يدوي). الباقي قابل للضبط يدويًا من الأدمن وفق آلة الحالات لكن لا شيء في الكود يولّدها تلقائيًا، ولا يوجد استرداد أموال (لا كيان `refund` في Java).
- حالة الدفع آلة منفصلة تمامًا عن حالة التنفيذ (راجع 3.1/3.2).

المصدر: `src/main/java/com/velora/api/order/domain/PaymentMethod.java` · `src/main/java/com/velora/api/order/service/CheckoutService.java:469-485` · `src/main/java/com/velora/api/order/domain/PaymentStatus.java` · `src/main/java/com/velora/api/order/service/OrderService.java:263-280`

### 1.4 أنواع التنفيذ (`FulfillmentType`) وكيف يختلف سلوك كل نوع

| النوع | يُضاف للسلة؟ | يحتاج `shippingSizeClass`؟ | النشر (`publish`) بدون متغيّرات؟ | كيف يُطلب |
|---|---|---|---|---|
| `READY_MADE` | نعم | **إلزامي** | **مرفوض** (`PRODUCT_HAS_NO_VARIANTS`) | سلة ← `POST /orders` |
| `MADE_TO_ORDER` | **لا** (`PRODUCT_NOT_PURCHASABLE`) | اختياري | مسموح | طلب خاص (`/custom-requests`) |
| `CUSTOM_WORK` | **لا** | اختياري | مسموح | طلب خاص |

- المنتجات من النوعين غير الجاهزين **تظهر في الكتالوج العام** (البحث وصفحة التفاصيل) ويمكن فلترتها بـ `fulfillmentType`، لكن الخادم يرفض إضافتها للسلة ولا يوجد حقل `purchasable` في `ProductDetailResponse` — العميل يستنتجه من `fulfillmentType`.
- طلب خاص من نوع `SIZE_VARIANT` يُشترط له منتج `READY_MADE` فعّال، وبُعد واحد على الأقل من `widthCm/heightCm/depthCm`.
- **تحويل الطلب الخاص إلى طلب عادي غير متاح**: الحالة `CONVERTED` موجودة لكن الخادم يرفض الانتقال إليها، والحقل `convertedOrderId` يبقى فارغًا.

المصدر: `src/main/java/com/velora/api/catalog/domain/FulfillmentType.java` · `src/main/java/com/velora/api/cart/service/CartService.java:305-316` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:157-173,416-421` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:178-202` · `src/main/java/com/velora/api/customrequest/service/CustomRequestAdminService.java:113-117` · `src/main/java/com/velora/api/catalog/dto/ProductDetailResponse.java`

### 1.5 الطلبات الخاصة (Custom Requests) والمعرض (Portfolio)

- **طلب خاص**: نموذج يرسله أي زائر (بدون حساب) يطلب مقاسًا مختلفًا أو منتجًا يُصنع بعد الطلب أو عملًا مخصصًا. ليس عملية بيع: لا حجز مخزون ولا تسعير حتى يعرض الموظف **عرض سعر (quote)**. الرقم `REQ-<سنة>-<6 أرقام>` متسلسل بلا فجوات لكل سنة (بتوقيت القاهرة).
- لا توجد نقطة نهاية للعميل لقراءة حالة طلبه الخاص أو سرد طلباته؛ العميل يحصل فقط على `requestNumber` ورمز مرفقات مرة واحدة عند الإنشاء. لا يُرسَل أي بريد أو إشعار عن الطلب الخاص.
- **المعرض (portfolio)**: كتالوج أعمال منجزة للعرض فقط (عنوان/وصف/صور/تصنيف/تاريخ إنجاز)؛ ليس منتجات قابلة للشراء. يبدأ غير منشور، ويُؤرشف بدل الحذف.
- **نطاق التصنيف (`category.scope`)**: المعرض والمتجر يتشاركان جدول `category`، والذي يفصل بينهما العمود `scope` = `PRODUCT` (الافتراضي، وكل التصنيفات الموجودة) أو `PORTFOLIO`.
  - تصنيف `PORTFOLIO` لا يظهر أبدًا في شجرة المتجر ولا في `GET /categories/{slug}` (404 كأنه مجهول) ولا في فلاتر المنتجات؛ وتصنيف `PRODUCT` لا يظهر في `GET /portfolio/categories` (قائمة **مسطّحة** لتصنيفات `PORTFOLIO` الفعّالة؛ تصير شجرة إن عششوا يومًا).
  - **الظهور يحدده النطاق و`active` فقط — لا `productCount`.** تصنيف `PRODUCT` فارغ يبقى ظاهرًا في المتجر عمدًا (العميل يريد رؤية الهيكل كاملًا قبل أن يملأه).
  - الابن يشارك نطاق أبيه، والمنتج لا يوضع إلا في تصنيف `PRODUCT` وعنصر المعرض لا يوضع إلا في تصنيف `PORTFOLIO` (عند الإنشاء **والتعديل**، في الاتجاهين) وإلا `CATEGORY_SCOPE_MISMATCH` (400). عنصر معرض **بلا تصنيف** يبقى صالحًا ويظهر في القائمة غير المفلترة. تغيير نطاق تصنيف مستخدم `CATEGORY_SCOPE_IN_USE` (409).
  - قاعدة الأب/الابن محفوظة في القاعدة نفسها: `UNIQUE (id, scope)` و`FOREIGN KEY (parent_id, scope)` (V21)، لا في كود الخدمة وحده.
  - **`slug` التصنيف فريد عبر النطاقين** (`uq_cat_slug` لم يُمسّ): «أبواب» معرض بجانب «أبواب» متجر يصير `أبواب-2`. تجميلي ونادر عمدًا، ولو جُعل الفريد `(slug, scope)` لصار `findBySlug` ملتبسًا بين النطاقين.

المصدر: `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:106-171` · `src/main/java/com/velora/api/customrequest/web/CustomRequestController.java` · `src/main/java/com/velora/api/portfolio/service/PortfolioAdminService.java:89-116,120-132` · `src/main/java/com/velora/api/portfolio/web/PortfolioController.java`

### 1.6 بقايا VELORA (مكوّنات موروثة)

الفروق التالية مؤكدة بالكود (وجود في المخطط أو التعريف بدون استخدام فعلي):

| البند | الحالة |
|---|---|
| جداول `credit_note`, `refund`, `return_request`, `return_item`, `payment_transaction`, `shipment`, `notification_log`, `notification_template`, `back_in_stock_subscription`, `store_setting`, `url_redirect`, `city` | موجودة في `baseline_through_V14.sql` **بلا أي كيان Java يستخدمها**. لا مرتجعات ولا إشعارات دائنة ولا استرداد ولا شحنات ولا اشتراكات "نبّهني عند التوفر". |
| 15 كود في `ErrorCode` لا يشير إليه أي كود آخر (تم التحقق بالبحث `ErrorCode.<NAME>` في كل ملفات `src/main/java`): `UNSUPPORTED_FILE_TYPE`, `VARIANT_COMBINATION_EXISTS`, `CATEGORY_NOT_EMPTY`, `RESERVATION_EXPIRED`, `PRICE_CHANGED`, `COUPON_NOT_FOUND`, `COUPON_EXPIRED`, `COUPON_USAGE_LIMIT_REACHED`, `COUPON_MINIMUM_NOT_MET`, `COUPON_NOT_APPLICABLE`, `RETURN_WINDOW_CLOSED`, `RETURN_QUANTITY_EXCEEDED`, `REFUND_EXCEEDS_ORDER_TOTAL`, `PURCHASE_REQUIRED`, `REVIEW_ALREADY_EXISTS` | معرّفة ولا تُرمى أبدًا. لا يجوز أن يبني العميل منطقًا عليها. |
| حالات الطلب `RETURNED`, `PARTIALLY_RETURNED` ونوعا الحركة `RETURN_SELLABLE`, `RETURN_DAMAGED`, `CANCELLATION_RESTOCK` | معرّفة؛ الانتقال إلى الحالتين الأوليين ممكن يدويًا لكنه **لا يُنتج أي حركة مخزون**، ولا شيء يكتب الأنواع الثلاثة. |
| `Permission` / `Role.permissions` | الكيانات موجودة؛ الحماية الفعلية `hasRole("ADMIN")` فقط. لا فرق بين أدمن وأدمن. |
| `velora.business.default-tax-rate` و`velora.business.prices-include-tax` | كانا في `application.yml` بلا أي كود يقرؤهما، **وقد حُذفا** من الملفات الثلاثة (main وtest وexample). معدل الضريبة الافتراضي 14% ثابت مسمّى في الكود `ProductVariant.DEFAULT_TAX_RATE` (راجع 2.3 و6.3). |
| `VELORA`/`VLR` في نصوص أمثلة Swagger والتعليقات واسم الملف `velora-orders-*.xlsx` و`velora-picking-*.pdf` و`ROYAL` في البادئات | الأسماء الظاهرة للعميل: البادئات من `velora.branding.*`؛ اسما ملفي التصدير مثبّتان بـ `velora-` في الكود. |
| `CartStatus.ABANDONED` | معرّفة، **لا شيء يضع السلة بهذه الحالة** (لا Job). |

المصدر: `src/main/resources/db/baseline/baseline_through_V14.sql:376,1111,1154,1133,902,1216,757,776,160,1387,1403,309` · `src/main/java/com/velora/api/common/exception/ErrorCode.java` · `src/main/java/com/velora/api/inventory/domain/MovementType.java` · `src/main/java/com/velora/api/identity/domain/Role.java:51` · `src/main/java/com/velora/api/common/security/SecurityConfig.java:122` · `src/main/java/com/velora/api/catalog/domain/ProductVariant.java:75` · `src/main/java/com/velora/api/export/web/AdminExportController.java:50,75` · `src/main/java/com/velora/api/cart/domain/CartStatus.java`

## 2. القواعد الثابتة (Invariants)

لكل قاعدة: **ما هي — لماذا تجاريًا — أين تُفرض — ماذا يحدث عند المحاولة.**

### 2.1 لقطة الطلب والفاتورة (Snapshot)

- **القاعدة:** كل ما يُعرض عن طلب تاريخي يُقرأ من الطلب نفسه لا من الكتالوج. عند الشراء تُنسخ على كل سطر: الاسم بالعربية `productNameAr` والإنجليزية `productNameEn`، `sku`، ملخص الخيارات `variantSummary` (نص واحد بلغة الطلب وقت الشراء، مثل "ذهبي / 42 مم")، رابط الصورة (URL كامل وقت الشراء)، `unitPriceGross`، `taxRate`، `lineTotalGross`، `lineTaxAmount`، `allocatedCartDiscount`، `assemblyFee`. وعلى الطلب: بيانات الاتصال (الاسم والهاتف E.164 والبريد) والعنوان كاملًا (`ship_*` + اسم المحافظة نصًا) واسم منطقة الشحن (`shippingZoneName`) وأيام التسليم المتوقعة، وتفصيل الشحن `shippingBreakdown` (JSON) و`shippingCapApplied` و`shippingUncappedCost`.
- **لماذا:** الأسعار والأسماء والعناوين وأسعار الشحن تتغير؛ الطلب والفاتورة وثيقتان قانونيتان يجب أن تبقيا كما صدرتا.
- **أين تُفرض:** `CheckoutService.captureLines` تقرأ القيم قبل أي كتابة، و`buildItems` و`applyContactAndAddress` تنسخها. العنوان **لا** يُربط بـ `customer_address` — حتى العنوان المحفوظ يُنسخ حقلًا حقلًا (`resolveAddress`). أعمدة الأسماء في `order_item` `NOT NULL`، ولذلك يرجع `nameFor` للعربية إن غابت ترجمة لغة (`CheckoutService.nameFor`).
- **الفاتورة:** عند `issueForOrder` تُنسخ على صف `invoice`: بيانات البائع (الاسم القانوني والعنوان والهاتف والبريد والرقم الضريبي والسجل التجاري)، بيانات المشتري (الاسم والهاتف والعنوان النصي المُركَّب `formattedAddress`)، وكل المبالغ، وطريقة الدفع. ملف الـ PDF يُعاد توليده عند كل طلب من هذه اللقطة ومن أسطر الطلب (لا من الكتالوج).
- **استثناءات/ثغرات مؤكدة في الكود (يجب أن يعرفها من يكتب وثيقة المستخدم):**
  1. **نص تذييل الفاتورة `invoiceFooterNote` يُقرأ حيًّا من `store_profile` عند كل توليد PDF** (`buildView`)، وليس من لقطة. تعديل ملف المتجر يغيّر تذييل الفواتير القديمة عند إعادة الطباعة، بخلاف بقية بيانات البائع التي تُنسخ. وتعليق `AdminStoreProfileController` ("Existing invoices keep the details...") وتعليق `AdminInvoiceController` ("identical every time") أوسع من الواقع بهذا القدر.
  2. حقل `slug` في `OrderItemResponse` يُقرأ من المنتج الحي (`item.getProduct().getSlug()`)، وليس من لقطة.
  3. `formattedAddress` يُنتج نصًا بكلمات إنجليزية ثابتة (`building`, `floor`, `apt`) ويُخزَّن هكذا في `invoice.buyerAddress` وفي ملصقات الطباعة.
- **عند المخالفة:** لا توجد "محاولة مخالفة" عبر الـ API؛ لا يوجد أي endpoint لتعديل سطر طلب أو لقطته.

المصدر: `src/main/java/com/velora/api/order/service/CheckoutService.java:277-301,369-399,403-436,438-465,487-495` · `src/main/java/com/velora/api/order/domain/OrderItem.java` · `src/main/java/com/velora/api/order/domain/CustomerOrder.java:264-283` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:100-102,350-404,386` · `src/main/java/com/velora/api/order/service/OrderService.java:467-488` · `src/main/java/com/velora/api/store/web/AdminStoreProfileController.java:46-48` · `src/main/java/com/velora/api/invoice/web/AdminInvoiceController.java:62-71`

### 2.2 الترقيم المتسلسل بلا فجوات وعلى ماذا يُبنى

الأنظمة الأربعة للترقيم مختلفة عمدًا:

| الرقم | الصيغة | هل هو بلا فجوات؟ | على ماذا يقوم العدّاد | آلية الضمان |
|---|---|---|---|---|
| **الفاتورة** `invoiceNumber` | `<invoice-number-prefix>-<السنة>-<6 أرقام>` مثل `RYL-INV-2026-000001` | **نعم** | صف في جدول `invoice_sequence` **لكل `fiscal_year`** (سنة ميلادية). **لا يتعلق بالبادئة ولا بالمتجر ولا بالطلب.** السنة تؤخذ من `LocalDate.now().getYear()` بتوقيت **الخادم الافتراضي** (وليس القاهرة). | قفل صف العدّاد `PESSIMISTIC_WRITE` داخل نفس معاملة الفاتورة (والفاتورة تُصدر داخل معاملة الانتقال إلى `DELIVERED`)، فالتراجع يُرجع الرقم. `ensureYear` يُنشئ صف السنة الجديدة بجملة ذرّية مُهيَّأة بأعلى رقم موجود. فهارس فريدة: `uq_invoice_number` و`uq_invoice_year_seq` (لا يتغيران أبدًا)، و`uq_invoice_order` فريد على الفواتير **غير الملغاة** فقط (filtered index، V19). |
| **الطلب الخاص** `requestNumber` | `REQ-<السنة>-<6 أرقام>` | **نعم** | `custom_order_request_sequence` لكل `fiscal_year`، والسنة **بتوقيت القاهرة** (`LocalDate.now(CAIRO)`) | نفس آلية قفل الصف + `uq_custom_req_number` + `uq_custom_req_year_seq` |
| **الطلب** `orderNumber` | `<order-number-prefix>-<yyMMdd>-<4 أرقام عشوائية>` (يتوسع إلى 6 أرقام بعد 20 تصادمًا) | **لا — عشوائي عمدًا** | لا عدّاد. التاريخ بتوقيت الخادم الافتراضي | `existsByOrderNumber` ثم القيد الفريد `uq_order_number`. السبب المعلن: لا يكشف حجم المبيعات ولا يُخمَّن. |
| **التسوية** `reference` | `REM-<السنة>-<4 أرقام>` | **لا ضمان** | `MAX(RIGHT(reference,4))+1` بدون قفل | يحميه فقط `uq_remittance_reference`؛ تسوّيتان متزامنتان قد تفشل إحداهما بـ`DUPLICATE_VALUE` (409). الأرقام لا تُعاد حتى لو أُلغيت التسوية. بعد 9999 في السنة يلتبس `RIGHT(…,4)`. |

- **لماذا:** الفاتورة وثيقة ضريبية ثغرة تسلسلها مشكلة محاسبية؛ رقم الطلب يُعطى للعميل ويجب ألا يكشف الحجم؛ رقم الطلب الخاص يُقال للعميل هاتفيًا.
- **ما يحدث عند الإلغاء وإعادة الإصدار:** الفاتورة الملغاة `CANCELLED` تبقى برقمها وصفها للأبد؛ رقمها لا يُحرَّر ولا يُعاد استخدامه. للطلب فاتورة **حيّة واحدة على الأكثر** وأي عدد من الملغاة. الإصدار بعد الإلغاء يُنتج فاتورة **جديدة برقم التالي في التسلسل** (k ثم k+1 ثم k+2 …) تشير إلى الملغاة عبر `replaces_invoice_id` (يظهر `replacesInvoiceNumber` في ردّ الأدمن)، فلا فجوة ولا تكرار. إن وُجدت فاتورة حيّة يعيدها الإصدار كما هي دون استهلاك رقم. سباق إصدارين متزامنين: الفهرس يرفض الثاني فتتراجع معاملته ويرجع الرقم إلى التسلسل (يظهر للمستخدم 409 `DUPLICATE_VALUE`). إلغاء الفاتورة وإعادة إصدارها يُسجَّلان في `audit_log` (`INVOICE_CANCELLED` و`INVOICE_REISSUED`). لا يوجد إشعار دائن (جدول `credit_note` موجود في المخطط بلا كود): التصحيح هنا إلغاء ثم إصدار بديل.
- **عند تغيير البادئة في `application.yml`:** تسري على الأرقام الجديدة فقط ولا يُعاد ترقيم القديم، لكن **العدّاد لا يبدأ من الصفر** لأنه مفتاحه السنة وحدها.
- **تنبيه:** الإصدار اليدوي `POST /api/v1/admin/invoices/issue/{orderId}` متاح للمدير ولا يُصدر إلا لطلب بحالة `DELIVERED`، وهو idempotent (يعيد الفاتورة الحيّة الموجودة؛ وإن كانت فواتير الطلب كلها ملغاة يُصدر بديلة).

المصدر: `src/main/java/com/velora/api/invoice/service/InvoiceService.java:100-102,199-213` · `src/main/java/com/velora/api/invoice/repository/InvoiceSequenceRepository.java:41,52` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:113-191` · `src/main/java/com/velora/api/invoice/domain/Invoice.java:60` · `src/main/resources/db/V19__replaceable_invoice_and_settlement.sql:87-89` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:150,208-219` · `src/main/java/com/velora/api/customrequest/repository/CustomOrderRequestSequenceRepository.java:42,51` · `src/main/java/com/velora/api/order/service/OrderNumberGenerator.java:44-66` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:279-283` · `src/main/java/com/velora/api/remittance/repository/CodRemittanceRepository.java:30` · `src/main/resources/db/baseline/baseline_through_V14.sql:564,1529-1533,1566-1570,1655-1659,1660-1664,1665-1670` · `src/main/resources/application.yml:133,134`

### 2.3 كيف تُحسب الضريبة وعلى أي أساس

- **الأسعار شاملة الضريبة (tax-inclusive).** الضريبة تُستخرج: `net = gross ÷ (1 + rate)` (تقريب `HALF_UP` إلى 4 خانات) و`tax = gross − net`.
- **تُحسب لكل سطر ثم تُجمع**، لا على إجمالي الطلب. الأساس لكل سطر: `lineTotalGross − allocatedCartDiscount` (والخصم صفر اليوم). المعدل `rate` هو `taxRate` **الخاص بالمتغيّر** المنسوخ على السطر.
- **رسوم التركيب** (`assemblyFee × quantity`) تُخضع للضريبة بمعدل السطر نفسه، وتُضاف الضريبة المستخرجة منها إلى `taxTotal`.
- **الشحن ورسوم COD غير خاضعين للضريبة في الحساب الحالي** — يدخلان في `grandTotal` ولا يخرج منهما ضريبة، و`netTotal = grandTotal − taxTotal`، أي أن `netTotal` يتضمن الشحن والـ COD كاملَين. التعليق في الكود يطلب تأكيد محاسب لهذه النقطة ولنقطة معدل رسوم التركيب (`TODO(accountant)`).
- **معدل الضريبة الافتراضي 14% ثابت في الكود لا إعداد:** `ProductVariant.DEFAULT_TAX_RATE = 0.1400`، يستعمله كيان المتغيّر و`VariantAdminService.createVariant` عند غياب `taxRate`. لا يوجد أي إعداد ضريبة في `application.yml` (المفتاحان القديمان `velora.business.default-tax-rate` و`prices-include-tax` حُذفا لأنهما لم يكونا مقروءين، والأسعار شاملة الضريبة بحكم الكود وليس بحكم إعداد). المعدل الحقيقي لكل متغيّر عمود `product_variant.tax_rate` (`taxRate` في `VariantSaveRequest`، بلا حد أعلى/أدنى في الـ DTO)، وقيد قاعدة البيانات الافتراضي `df_var_tax` = 0.1400 يسري على الإدخال المباشر في SQL فقط. **قاعدة اللقطة:** المعدل ينتقل إلى سطر الطلب (`order_item.tax_rate`) عند الشراء، فتغيير معدل المتغيّر لاحقًا لا يمس طلبًا قائمًا ولا فاتورة صادرة (مغطّى باختبار `TaxSnapshotIntegrationTest`).
- **الفاتورة:** (تنبيه: أسطرها المطبوعة **لا تتضمن رسوم التركيب**، فعند `assemblyTotal > 0` لا يساوي مجموع ضرائب الأسطر المطبوعة `taxTotal` المنسوخ من الطلب.) تعيد حساب ضريبة كل سطر من `lineTotalGross − allocatedCartDiscount − lineDiscount` بنفس `MoneyUtils.taxFromGross` وتعرض نسبة الضريبة `taxRate × 100`. مجاميع الفاتورة (`taxTotal`, `netTotal`, ...) تُنسخ من الطلب لا تُعاد.
- **عند المخالفة:** `MoneyUtils` يرمي `IllegalArgumentException` لقيمة سالبة أو null؛ `GlobalExceptionHandler` يحوّله إلى `INVALID_PARAMETER` (400) برسالة الاستثناء.
- كل المبالغ `BigDecimal` والتخزين `DECIMAL(19,4)`؛ لا يُستعمل `roundForDisplay` (تقريب خانتين) في أي مسار فعلي (البحث لم يجد استدعاءً له خارج `MoneyUtils`).

المصدر: `src/main/java/com/velora/api/common/util/MoneyUtils.java:59-64,67-69,114-145,30` · `src/main/java/com/velora/api/order/service/CheckoutService.java:310-366,369-399` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:350-404` · `src/main/java/com/velora/api/catalog/domain/ProductVariant.java:75,78` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:180,216-218` · `src/main/java/com/velora/api/catalog/dto/admin/VariantSaveRequest.java:50` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:130-134`

### 2.4 الشحن: التسعير لكل قطعة وكيف يعمل السقف

1. المحافظة تحدد المنطقة (`shipping_zone`) عبر `shipping_zone_governorate`. محافظة بلا منطقة (أو بمنطقة بلا أسعار) = **غير مخدومة** → `GOVERNORATE_NOT_SERVED` (409) في العرض والـ checkout وإنشاء العنوان.
2. كل سطر في السلة يساهم بـ `quantity` تحت **حجم الطرد `shippingSizeClass` لمنتجه** (`SMALL|MEDIUM|LARGE`). تُجمَّع الكميات حسب الحجم عبر السطور.
3. لكل حجم: `تكلفة = baseCost(المنطقة، الحجم) × إجمالي كمية ذلك الحجم`. **السعر لكل قطعة**، لا لكل شحنة ولا بالوزن. المجموع قبل السقف = `uncappedCost`.
4. **السقف** `maxShippingCost` خاصية **المنطقة** (`shipping_zone.max_shipping_cost`، قد تكون `null` = بلا سقف). `shippingCost = min(uncappedCost, cap)`؛ و`capApplied = (cap != null && uncappedCost > cap)`. السقف يغطي الشحن فقط.
5. **رسوم COD** `codFee` خاصة بالمنطقة، تُضاف **فوق** الشحن ولا يسري عليها السقف، وتُحسب دائمًا لأن COD هي الطريقة الوحيدة (`codApplies = true` في العرض وفي الـ checkout).
6. الوزن `weightGrams` لا يؤثر على السعر (يُعرض معلوماتيًا فقط). عتبة الشحن المجاني القديمة لم تعد تُقرأ: `freeShippingThreshold` و`amountToFreeShipping` في `ShippingQuoteResponse` **دائمًا `null`**.
7. **الأخطاء قبل أي كتابة:** منتج بلا `shippingSizeClass` → `SHIPPING_SIZE_MISSING` (409)؛ منطقة بلا سعر لحجم مطلوب → `SHIPPING_RATE_NOT_CONFIGURED` (409). لا يُخمَّن السعر أبدًا.
8. **اللقطة على الطلب:** `shippingCost` (بعد السقف)، `shippingUncappedCost`، `shippingCapApplied`، `shippingBreakdown` (JSON بحقول `sizeClass, quantity, unitCost, lineCost` بترتيب الأكبر حجمًا أولًا)، `codFee`، اسم المنطقة، `deliveryDaysMin/Max`. عمود `shipping_breakdown` طوله 500 حرف.
9. **التعديل الإداري:** `PUT /admin/shipping/rates` يضبط سعر حجم واحد لمنطقة؛ `codFee` و`deliveryDaysMin/Max` خصائص المنطقة كلها فتُكتب على كل صفوف أحجامها. ضبط السقف عبر `PUT /admin/shipping/zones/{zoneId}/max-shipping-cost` (`null` يزيل السقف). فتح محافظة (`PUT .../governorates/{id}/zone`) مرفوض إن لم تكن المنطقة مسعَّرة لكل الأحجام الثلاثة. كل التغييرات تُسجَّل في `audit_log`.
10. قيم الـ seed (للمرجع فقط، ليست بالضرورة القيم الحية): منطقة `GREATER_CAIRO` (القاهرة والجيزة والقليوبية) سعرها صفر للأحجام الثلاثة؛ سقف 1500 لكل المناطق الست؛ جنوب سيناء وأسوان والأقصر بلا منطقة (مغلقة).

المصدر: `src/main/java/com/velora/api/shipping/service/ShippingCalculator.java:47-87,90-106` · `src/main/java/com/velora/api/shipping/service/ZoneRates.java` · `src/main/java/com/velora/api/shipping/service/ShippingService.java:103-145,151-153,159-169` · `src/main/java/com/velora/api/order/service/CheckoutService.java:142,161,195,202` · `src/main/java/com/velora/api/order/domain/CustomerOrder.java:168` · `src/main/java/com/velora/api/shipping/service/ShippingAdminService.java:108-178,182-201,251-287,294-310` · `src/main/java/com/velora/api/shipping/web/AdminShippingController.java` · `src/main/java/com/velora/api/shipping/dto/ShippingQuoteResponse.java` · `src/main/resources/db/baseline/seed_reference_data.sql:59-69,101-131`

### 2.5 حذف الحقول null من الاستجابات

- **القاعدة:** كل استجابة JSON تحذف الحقول التي قيمتها `null` — فالغياب = لا قيمة. الآلية الوحيدة إعداد `spring.jackson.default-property-inclusion: non_null` (لا توجد `@JsonInclude` في الكود).
- **التحقق:** اختبارات `OrderDeliveryScheduleIntegrationTest` تؤكد `jsonPath("$.scheduledDeliveryAt").doesNotExist()` على الاستجابة الفعلية.
- **ما يترتب عليه للعميل:** لا يمكن التمييز بين "الحقل لا ينطبق" و"الحقل فارغ"؛ أي حقل اختياري يجب أن يُقرأ كـ `undefined`. الأعداد والمنطقيات غير الـ null تظهر دائمًا (مثل `shippingCapApplied` المحسوب بـ `Boolean.TRUE.equals`).
- **تنبيه تقني (استنتاج، لم أشغّل الاختبارات):** يوجد `ObjectMapper` مخصص في `JacksonConfig` **لا يضبط** حذف null، ويستعمله الكود الداخلي (تخزين Idempotency وتحويل `shippingBreakdown`). بما أن اختبارات المشروع تتوقع غياب الحقول الفارغة في استجابات HTTP، فالمفترض أن طبقة HTTP لا تستعمل هذا الـ mapper. إن استُبدل mapper طبقة HTTP يومًا بهذا الـ bean فستعود الحقول `null` إلى الاستجابات. جسم الاستجابة المخزَّن في `idempotency_key` قد يحوي `null`.

المصدر: `src/main/resources/application.yml:31` · `src/main/java/com/velora/api/common/config/JacksonConfig.java:20-38` · `src/test/java/com/velora/api/order/OrderDeliveryScheduleIntegrationTest.java:558,574` · `src/main/java/com/velora/api/order/service/OrderService.java:433`

### 2.6 Idempotency

- **النطاق:** `POST /api/v1/orders` فقط (الـ endpoint اسمه في الكود `"POST /api/v1/orders"`). لا يوجد أي endpoint آخر يدعمه.
- **الرأس:** `Idempotency-Key` **اختياري**؛ بدونه يُنفَّذ الطلب عاديًا (قد ينتج طلبان عند ضغطتين).
- **السلوك:**
  - المفتاح جديد: يُحجز بصف `IN_PROGRESS` في معاملة مستقلة (`REQUIRES_NEW`) — حتى لا يُلغى الحجز لو تراجعت معاملة الطلب — ثم يُنفَّذ الطلب ويُخزَّن الرد `COMPLETED`.
  - المفتاح مكرر + نفس الجسم + `COMPLETED`: **يُعاد نفس الرد المخزَّن** (والـ controller يعيد 201 دائمًا).
  - المفتاح مكرر + جسم مختلف (يُقارن SHA-256 لـ JSON جسم الطلب): `VALIDATION_FAILED` (400) "already used with a different request".
  - المفتاح مكرر + ما زال `IN_PROGRESS`: `DUPLICATE_ORDER` (409).
  - المفتاح مكرر + `FAILED` (فشل الطلب الأول بأي `RuntimeException`، أي أيضًا `CART_EMPTY` أو `VALIDATION_FAILED` أو نفاد المخزون — الأخطاء كلها تحرق المفتاح): `VALIDATION_FAILED` (400) "Retry with a new Idempotency-Key". **أي أن المفتاح الفاشل لا يُعاد استخدامه**، خلافًا لتعليق في الكود يقول إن الفاشل "قابل لإعادة المحاولة بنفس المفتاح".
- **المدة:** صلاحية السجل 24 ساعة (`RETENTION_HOURS`) تُخزَّن في `expires_at`، لكن **لا شيء يفحص `expires_at` عند الحجز أو القراءة**؛ الحذف يتم فقط بمهمة التنظيف اليومية 03:30 (توقيت الخادم). فمفتاح منتهٍ لم يُنظَّف بعد ما زال يُعيد الرد القديم أو يتعارض.
- **الفرادة:** `(idempotency_key, endpoint)` — **غير مرتبطة بالمستخدم**؛ `userId` يُخزَّن ولا يُقارن عند الإعادة. الحماية العملية أن الجسم يجب أن يتطابق (يشمل الاسم والهاتف والعنوان). رأس `X-Guest-Token` لا يدخل في الـ hash.
- **قيد تصميمي مستنتج من تسلسل الاستدعاءات:** تُسجَّل النتيجة بعد اكتمال معاملة الطلب، ولا يوجد مسار لإنهاء سجل ظل `IN_PROGRESS` إذا انتهت العملية بين الاثنين؛ يبقى المفتاح "قيد المعالجة" إلى أن ينظّفه الجدول الزمني بعد 24 ساعة.

المصدر: `src/main/java/com/velora/api/order/web/CheckoutController.java:74-112` · `src/main/java/com/velora/api/idempotency/service/IdempotencyService.java:61-88,97-125,43` · `src/main/java/com/velora/api/idempotency/service/IdempotencyStore.java:48-65,90-96` · `src/main/java/com/velora/api/idempotency/service/IdempotencyCleanupJob.java:27` · `src/main/resources/db/baseline/baseline_through_V14.sql:625-647,1634-1638`

### 2.7 Optimistic locking

- `@Version` موجود على كيان **واحد فقط: `Inventory`** (`version`). لا يوجد على الطلب أو المنتج أو الفاتورة أو غيرها.
- التعديل اليدوي للمخزون (`receive`/`adjust`) يقرأ صف المخزون كيانًا ثم يحفظه؛ لو غيّره حجز/شحن متزامن بين القراءة والحفظ يفشل الحفظ بـ `OptimisticLockingFailureException`.
- كل جمل الـ UPDATE الأصلية (`tryReserve`, `releaseReservation`, `commitReservation`) تزيد `version = version + 1` يدويًا.
- **عند التعارض:** `GlobalExceptionHandler` يعيد `CONCURRENT_STOCK_CHANGE` (409) — **لأي** `OptimisticLockingFailureException` أيًا كان الكيان. يُنتظر من العميل إعادة المحاولة.
- ليس بديلًا عن الحجز الذرّي، بل الخط الثاني للدفاع (كما في تعليق `Inventory`).

المصدر: `src/main/java/com/velora/api/inventory/domain/Inventory.java:37-39` · `src/main/java/com/velora/api/inventory/repository/InventoryRepository.java:44,54,68` · `src/main/java/com/velora/api/inventory/service/InventoryAdminService.java:149-210` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:220-224`

### 2.8 المخزون

- ثلاثة أرقام: `qtyOnHand` و`qtyReserved` والمشتق `available = onHand − reserved` (لا يُخزَّن؛ `Inventory.getAvailable()` و`@Formula` للفرز/التصفية). قيود قاعدة البيانات: `qty_on_hand ≥ 0`, `qty_reserved ≥ 0`, `qty_reserved ≤ qty_on_hand`.
- **الحجز ذرّي بجملة UPDATE بشرط:** `WHERE (qty_on_hand − qty_reserved) >= :qty`. إن لم يُصب صفًا (0) يرمي `STOCK_UNAVAILABLE` (409) وتتراجع المعاملة كلها (كل الحجوزات في نفس الطلب). لا قراءة-ثم-كتابة.
- **السلة لا تحجز شيئًا.** الحجز يحدث فقط داخل `placeOrder` وتُربط الحجوزات بالطلب في المعاملة نفسها (`attachToOrder`).
- **حركات المخزون (`stock_movement`)** تُكتب مع كل تغيير في `qty_on_hand`: `SALE` عند `SHIPPED` (سالب)، `PURCHASE_RECEIVED` عند الاستلام وعند الرصيد الافتتاحي للمتغيّر الجديد، و`MANUAL_ADJUSTMENT` أو `DAMAGE_WRITEOFF` أو `PURCHASE_RECEIVED` يدويًا. تغييرات `qty_reserved` وحدها (حجز/إفراج) **لا** تكتب حركة. كل حركة تحمل `qtyAfter` واسم المنفِّذ منسوخًا وقت الكتابة.
- **التعديل اليدوي:** السبب `reason` إلزامي (`@NotBlank`)؛ الدلتا ≠ 0؛ لا ينزل `onHand` تحت 0 (`NEGATIVE_STOCK`, 400) ولا تحت المحجوز (`STOCK_BELOW_RESERVED`, 409). الأنواع `SALE, RETURN_SELLABLE, RETURN_DAMAGED, CANCELLATION_RESTOCK` ممنوعة يدويًا (`MOVEMENT_TYPE_NOT_MANUAL`, 400).
- **مدة الحجز (`reservation-ttl-minutes = 20`):** المهمة الدورية (كل دقيقة، دفعة 200) تحرّر الحجوزات `HELD` المنتهية **التي بلا `orderId` فقط**. لأن حجوزات الـ checkout تُربط بالطلب فورًا في نفس المعاملة، فالمهمة عمليًا لا تجد ما تحرره من هذا المسار؛ وحجز الطلب يبقى حتى الشحن أو الإلغاء مهما طال.
- **الإلغاء قبل الشحن / `RETURNED_TO_SELLER`:** يُحرَّر الحجز (`qty_reserved` يرجع) دون حركة. **بعد الشحن لا يُعاد أي مخزون تلقائيًا**: `RETURNED_TO_SELLER` بعد `SHIPPED` يجد الحجوزات `COMMITTED` فلا يفعل شيئًا، ولا يوجد تدفق مرتجعات/فحص؛ لذلك تُرجَع البضاعة إلى الرصيد يدويًا بتعديل `MANUAL_ADJUSTMENT`. القاعدة "لا إعادة للمخزون إلا بعد الفحص" **غير منفَّذة بمسار** لأن لا مسار مرتجعات أصلًا.
- التعليق في `reserveAll` يقول إن الترتيب المتسق للمتغيّرات يمنع الـ deadlock، لكن الكود يستعمل `LinkedHashMap` بترتيب سطور السلة دون فرز (راجع 8).

المصدر: `src/main/java/com/velora/api/inventory/domain/Inventory.java:83-85` · `src/main/java/com/velora/api/inventory/repository/InventoryRepository.java:44` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:68-112,132-141,162-201,209-219` · `src/main/java/com/velora/api/inventory/repository/StockReservationRepository.java:38-39` · `src/main/java/com/velora/api/inventory/service/ReservationCleanupJob.java:30-47` · `src/main/java/com/velora/api/inventory/service/InventoryAdminService.java:149-210,212-231` · `src/main/java/com/velora/api/inventory/dto/StockAdjustRequest.java` · `src/main/java/com/velora/api/order/service/OrderService.java:334-342,163` · `src/main/resources/db/baseline/baseline_through_V14.sql:679,683,687`

### 2.9 صلاحية الكائن (Object-level) وعدم التمييز بين "غير موجود" و"ليس لك"

- كل `/api/v1/admin/**` يتطلب `ROLE_ADMIN`؛ وكل ما عدا القائمة العامة يتطلب مصادقة (`anyRequest().authenticated()`). **401 و403 مفصولان:** طلب بلا رمز أو برمز منتهٍ/تالف يُعاد `401 UNAUTHORIZED`، ومستخدم مسجَّل بلا الدور اللازم يُعاد `403 FORBIDDEN`، كلاهما بجسم الخطأ القياسي (`code` في الجسم). كان الخادم يعيد 403 في الحالتين (لا `AuthenticationEntryPoint`)، فكان العميل يعامل أي 403 كجلسة منتهية؛ أُصلح بـ`authenticationEntryPoint` و`accessDeniedHandler` في `SecurityConfig`. المسارات العامة (السلة، الطلب كضيف، عرض سعر الشحن، الكتالوج) لا تتأثر حتى لو أُرسل رمز منتهٍ.
- **طلبات العميل:** `findByOrderNumberAndCustomerId` — رقم طلب غيرك = نفس `ORDER_NOT_FOUND` (404). نفس النمط: العناوين (`RESOURCE_NOT_FOUND` 404)، الفواتير (`/me/invoices/{invoiceNumber}/pdf` → 404)، بنود السلة (`CART_ITEM_NOT_FOUND`)، المرفقات (404 مطابق لمعرّف غير موجود).
- **ضيوف السلة:** رمز `X-Guest-Token` = `<uuid>.<HMAC-SHA256 base64url>`؛ التوقيع إلزامي (`TOKEN_INVALID` 401 إن كان خاطئًا). **لكن** `velora.guest-token.accept-legacy-unsigned: true` في الإعداد الحالي يقبل الرموز غير الموقّعة (UUID خام) مع تحذير في السجل — أي أن الحماية معطّلة عمليًا للسلال القديمة ريثما تُغلق هذه الخاصية.
- **ثغرات وُجدت وما آل إليه كل منها:**
  1. **أُصلحت:** `POST /api/v1/shipping/quote` مع `cartId` صريح صار يشترط أن تكون السلة للمتصل (المستخدم المسجَّل صاحبها، أو الضيف الذي يحمل رمزها الموقَّع)، وإلا `404 RESOURCE_NOT_FOUND` مطابقًا لسلة غير موجودة (معرّفات السلال أعداد صحيحة متسلسلة فكانت قابلة للتعداد). سلة غير موجودة بمعرّف صريح كانت `CART_EMPTY` 400 وصارت 404.
  2. **أُصلحت للفواتير:** ملفات PDF الفواتير (`uploads/invoices/<yyyy>/<MM>/<invoiceNumber>.pdf`، وتحوي اسم المشتري وهاتفه وعنوانه) صارت **مغلقة**: `/uploads/invoices/**` مرفوض لكل الناس (401 للمجهول و403 للمسجَّل، حتى الأدمن) في `SecurityConfig`، ومحجوب أيضًا في طبقة الموارد `StorageConfig` احتياطًا. الملفات ما زالت تُكتب على القرص لكن لا يصل إليها HTTP؛ التنزيل فقط عبر `GET /admin/invoices/{id}/pdf` (أدمن) و`GET /me/invoices/{invoiceNumber}/pdf` (صاحب الطلب)، وحقل `pdfUrl` في ردّ الأدمن صار مسار الأول. **ما يبقى عامًا عمدًا:** `/uploads/products/` و`/uploads/categories/` و`/uploads/portfolio/` (صور المتجر) و`/uploads/custom-requests/` (راجع 7.5 — قرار مقصود).
  3. الـ access token بلا فحص قاعدة بيانات في كل طلب (الدور يُقرأ من الـ JWT)؛ تعليق/إيقاف حساب لا يُبطل access token الصادر قبل ذلك (حتى `access-token-minutes = 30`)، لكن `refresh` يرفض الحساب غير الفعّال.
  4. طلب الضيف (بدون حساب): لا يوجد أي endpoint لقراءته لاحقًا؛ الاستجابة الوحيدة هي رد `POST /orders`. (يُبنى هذا الرد بـ `getForAdmin`، أي نفس شكل ردّ الأدمن الكامل بما فيه `timeline`).
- كلمة المرور BCrypt بقوة 12 (`PasswordEncoder`)، طولها 8–72 حرفًا؛ لا تُسجَّل الهواتف إلا مقنَّعة (`PhoneNormalizer.mask`)، لكن **البريد الإلكتروني يُسجَّل كاملًا** في سجلات التسجيل والإرسال.

المصدر: `src/main/java/com/velora/api/common/security/SecurityConfig.java:85-128,47-61,64-73,153` · `src/main/java/com/velora/api/order/service/OrderService.java:90-95,296-310` · `src/main/java/com/velora/api/customer/service/AddressService.java:177-183` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:259-266` · `src/main/java/com/velora/api/cart/security/GuestTokenService.java:81-110` · `src/main/resources/application.yml:96` · `src/main/java/com/velora/api/shipping/web/ShippingController.java:43-56` · `src/main/java/com/velora/api/shipping/service/ShippingService.java:173-199,209-218` · `src/main/java/com/velora/api/common/security/SecurityConfig.java:131-145` · `src/main/java/com/velora/api/common/config/StorageConfig.java:31-51` · `src/main/java/com/velora/api/common/storage/LocalStorageService.java:79-101` · `src/main/java/com/velora/api/identity/security/JwtAuthenticationFilter.java:39-74` · `src/main/java/com/velora/api/identity/service/AuthService.java:173-192,115` · `src/main/java/com/velora/api/order/web/CheckoutController.java:74-112` · `src/main/java/com/velora/api/identity/dto/RegisterRequest.java:34`

### 2.10 الهاتف والعربية

- **الهاتف:** `PhoneNormalizer.toE164` يقبل **أرقام الموبايل المصرية فقط** (بادئات `010 011 012 015`، يتحمّل الأرقام العربية-الهندية والمسافات و`+20`/`0020`). **أرضي أو دولي = `INVALID_PHONE_FORMAT` (400).** يُطبَّق قبل: التسجيل، تسجيل الدخول (`resolveIdentifier`)، OTP، عنوان الـ checkout المباشر، العنوان المحفوظ، الطلب الخاص، بحث الأدمن بالهاتف في الطلبات والعملاء والطلبات الخاصة. التخزين `+20XXXXXXXXXX`، والعرض يعيده بالصيغة المحلية `01XXXXXXXXX` (`toLocalFormat`). `mask` يعطي `0101****678`.
- **العربية:** `ArabicNormalizer.normalize` يوحّد الألف (`أإآٱ→ا`) والتاء المربوطة (`ة→ه`) والألف المقصورة (`ى→ي`) والهمزات (`ؤ→و`, `ئ→ي`) ويحذف التشكيل والتطويل ويحوّل الأرقام العربية-الهندية ويحذف علامات الترقيم (يُبقي الحروف العربية واللاتينية والأرقام والمسافة).
- **الكتابة والاستعلام بنفس الدالة:** `product_translation.search_text` = `normalize(name + " " + shortDescription)` (يُقتطع إلى 800 حرف) عند الإنشاء/التعديل/النسخ؛ والاستعلام في `ProductSpecifications.matches` = `LIKE %كلمة1%كلمة2%` على `search_text` **للغة الطلب** (`Accept-Language`). **البحث العام يغطي الاسم والوصف القصير فقط** — لا الوصف الطويل ولا العلامة ولا التصنيف. بحث الأدمن في المخزون يطبّع الاستعلام كذلك.
- **ثغرة:** `search_text` لا يُحدَّث إلا عند حفظ المنتج؛ لا يوجد تحديث مجمّع.

المصدر: `src/main/java/com/velora/api/common/util/PhoneNormalizer.java:28-61,68-74,77-83` · `src/main/java/com/velora/api/common/util/ArabicNormalizer.java:40-64` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:288-296,311` · `src/main/java/com/velora/api/catalog/spec/ProductSpecifications.java:188-211` · `src/main/java/com/velora/api/identity/service/AuthService.java:293-300` · `src/main/java/com/velora/api/inventory/spec/InventorySpecifications.java:49`

### 2.11 قواعد أخرى مكتشفة

- **سجل حالات الطلب:** كل تغيير في حالة التنفيذ أو الدفع عبر `OrderService` يكتب صفًا في `order_status_history` (من/إلى/ملاحظة/المنفِّذ). **استثناء:** تسوية COD تغيّر `paymentStatus` مباشرة (`PENDING→PAID`) **بدون** صف في السجل وبدون المرور بآلة الحالات؛ يُسجَّل فقط في `audit_log` (`PAYMENT_ADJUSTED`).
- **الأرشفة بدل الحذف:** المنتجات، المتغيّرات، عناصر المعرض (`archived_at`)، العناوين (soft delete `deletedAt`)، الفواتير (`CANCELLED`)، التسويات (`CANCELLED`). الطلبات لا تُحذف أبدًا.
- **سلاج التصنيف والمنتج:** إعادة حفظ تصنيف بنفس `slug` لم تعد تغيّره إلى `<slug>-2` (كان فحص التفرّد يرى صف التصنيف نفسه كتعارض؛ صار يستثني معرّفه). تعارض حقيقي مع تصنيف آخر ما زال يُعطي اللاحقة `-2`. **سلاج المنتج المؤرشف يبقى له للأبد** — قرار مقصود، راجع 7.5.
- **تسجيل التدقيق `audit_log`:** يُكتب في معاملة مستقلة (`REQUIRES_NEW`) فيبقى حتى لو تراجعت المعاملة الخارجية. الأحداث المسجَّلة فعليًا: `PRICE_CHANGED`, `STOCK_ADJUSTED`, `STOCK_RECEIVED`, `SHIPPING_RATE_CHANGED`, `GOVERNORATE_SERVICE_CHANGED`, `CUSTOM_REQUEST_STATUS_CHANGED`, `CUSTOM_REQUEST_QUOTED`, `ORDER_DELIVERY_SCHEDULED`, `PORTFOLIO_STATUS_CHANGED`, `PAYMENT_ADJUSTED`, و`INVOICE_CANCELLED` و`INVOICE_REISSUED` (الأخير جديد: يحمل رقم الفاتورة الملغاة والبديلة). **معرّفة ولا تُكتب أبدًا:** `PRODUCT_PUBLISHED`, `PRODUCT_ARCHIVED`, `PERMISSION_CHANGED`, `STORE_PROFILE_CHANGED` — أي أن **تعديل ملف المتجر ونشر/أرشفة المنتج لا يتركان أثرًا في سجل التدقيق**.
- **جدولة الأحداث:** البريد يُرسل بعد الالتزام (`AFTER_COMMIT`) وفشله لا يؤثر على الطلب.
- **التقسيم إلى صفحات:** قوائم الأدمن الرئيسية تعيد `PageResponse` (`content, page, size, totalElements, totalPages, first, last, empty`)، لكن بعض الـ endpoints تعيد قوائم مسطحة أو `Map` — راجع 4/7.
- **أخطاء:** كل خطأ عمل `BusinessException(ErrorCode)` يُحوَّل إلى RFC 7807 (`type, title, status, detail, instance, code, timestamp` + `errors[]` للتحقق من الحقول).

المصدر: `src/main/java/com/velora/api/order/service/OrderService.java:263-280,273` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:172,177` · `src/main/java/com/velora/api/audit/service/AuditService.java:51-73,77-80` · `src/main/java/com/velora/api/audit/domain/AuditAction.java` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:222-246` · `src/main/java/com/velora/api/store/service/StoreProfileService.java:58-77` · `src/main/java/com/velora/api/notification/service/OrderConfirmationListener.java:56-81` · `src/main/java/com/velora/api/common/dto/PageResponse.java` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:282-292`

## 3. آلات الحالة (State Machines)

القاعدة العامة: قواعد الانتقال تُفرض في الخادم (`OrderStatusMachine` وغيره) لا في واجهة المستخدم. أي انتقال غير مسموح يرفضه الخادم بـ `INVALID_STATUS_TRANSITION` (HTTP 409) — ويكون نص الرسالة قائمة الانتقالات المسموحة من الحالة الحالية.

### 3.1 حالة تنفيذ الطلب (`FulfillmentStatus`) — 13 حالة

**الحالات:** `PENDING, CONFIRMED, AWAITING_SCHEDULE, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, REFUSED_ON_DELIVERY, RETURNED_TO_SELLER, CANCELLED, RETURNED, PARTIALLY_RETURNED`.

```
المسار الرئيسي:
PENDING ─► CONFIRMED ─► [AWAITING_SCHEDULE] ─► PROCESSING ─► SHIPPED ─► OUT_FOR_DELIVERY ─► DELIVERED
                    └───────────(اختياري: CONFIRMED ─► PROCESSING مباشرةً)──┘

الإلغاء (قبل الشحن فقط):
PENDING | CONFIRMED | AWAITING_SCHEDULE | PROCESSING ─► CANCELLED

فشل التسليم:
SHIPPED ─► DELIVERY_FAILED | RETURNED_TO_SELLER
OUT_FOR_DELIVERY ─► DELIVERY_FAILED | REFUSED_ON_DELIVERY
DELIVERY_FAILED ─► OUT_FOR_DELIVERY (إعادة محاولة) | REFUSED_ON_DELIVERY | RETURNED_TO_SELLER
REFUSED_ON_DELIVERY ─► RETURNED_TO_SELLER

بعد التسليم:
DELIVERED ─► RETURNED | PARTIALLY_RETURNED ─► RETURNED
```

الجدول الدقيق للانتقالات المسموحة (من `OrderStatusMachine`):

| من | إلى (المسموح فقط) |
|---|---|
| `PENDING` | `CONFIRMED`, `CANCELLED` |
| `CONFIRMED` | `AWAITING_SCHEDULE`, `PROCESSING`, `CANCELLED` |
| `AWAITING_SCHEDULE` | `PROCESSING` (يشترط `scheduledDeliveryAt`), `CANCELLED` |
| `PROCESSING` | `SHIPPED`, `CANCELLED` |
| `SHIPPED` | `OUT_FOR_DELIVERY`, `DELIVERY_FAILED`, `RETURNED_TO_SELLER` |
| `OUT_FOR_DELIVERY` | `DELIVERED`, `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY` |
| `DELIVERY_FAILED` | `OUT_FOR_DELIVERY`, `REFUSED_ON_DELIVERY`, `RETURNED_TO_SELLER` |
| `REFUSED_ON_DELIVERY` | `RETURNED_TO_SELLER` |
| `DELIVERED` | `RETURNED`, `PARTIALLY_RETURNED` |
| `PARTIALLY_RETURNED` | `RETURNED` |
| `CANCELLED`, `RETURNED`, `RETURNED_TO_SELLER` | — (نهائية) |

- **ملاحظات:** لا انتقال مباشر من `SHIPPED` إلى `DELIVERED` (يجب المرور بـ `OUT_FOR_DELIVERY`). الانتقال إلى نفس الحالة مرفوض ("The order is already X"). لا انتقال للخلف سوى الحلقة `DELIVERY_FAILED ↔ OUT_FOR_DELIVERY` (إعادة المحاولة). `DELIVERED` **ليست نهائية فعليًا**: يخرج منها `RETURNED`/`PARTIALLY_RETURNED`، رغم أن `FulfillmentStatus.isTerminal()` تعدّها نهائية (والدالة غير مستخدمة في أي منطق).
- **من يُطلقها:**
  - الأدمن: `PATCH /api/v1/admin/orders/{orderId}/fulfillment-status` (جسم: `status`, `note`)، و`POST .../confirm` (يذهب إلى `CONFIRMED`، الملاحظة الافتراضية "Confirmed by phone")، و`POST .../cancel`.
  - العميل المصادَق: `POST /api/v1/me/orders/{orderNumber}/cancel` — إلى `CANCELLED` فقط، ولطلباته فقط، وبشرط `isCancellable()`.
  - لا شيء تلقائي: لا Job يغيّر حالة الطلب.
- **ملاحظة إلزامية:** تُطلب `note` غير فارغة (وإلا `VALIDATION_FAILED` 400) عند الانتقال إلى `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY`, `CANCELLED`, `RETURNED_TO_SELLER`. نقطتا الإلغاء (`/cancel` للعميل وللأدمن) تضعان تلقائيًا "Cancelled by customer/staff" فقط إذا كان `reason` **`null`** (غائبًا)؛ فلا تفشلان لغيابه، **لكن سببًا فارغًا أو مسافات (`""`/`"  "`) يُرفض بـ`VALIDATION_FAILED`** — رغم أن وصف الـ controller يقول "A reason is required".
- **شرط خاص:** `AWAITING_SCHEDULE → PROCESSING` بدون `scheduledDeliveryAt` = `DELIVERY_NOT_SCHEDULED` (409). `CONFIRMED → PROCESSING` مباشرة لا يحتاج موعدًا.
- **الآثار الجانبية لكل انتقال** (كل انتقال ناجح يكتب أيضًا صفًا في `order_status_history` بنوع `FULFILLMENT` ويُحدّث `updated_at`):

| إلى | الأثر |
|---|---|
| `CONFIRMED` | `confirmed_at` = الآن. |
| `AWAITING_SCHEDULE`, `PROCESSING`, `OUT_FOR_DELIVERY`, `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY`, `RETURNED`, `PARTIALLY_RETURNED` | لا أثر آخر. **لا مخزون ولا مال ولا بريد.** |
| `SHIPPED` | `shipped_at`؛ **تثبيت الحجز**: لكل حجز `HELD` للطلب يصير `COMMITTED` وينقص `qty_on_hand` و`qty_reserved` معًا، وتُكتب حركة `SALE` (سالبة) بمنفِّذها. إن كان `on_hand` أقل من المحجوز: `STOCK_UNAVAILABLE` ويتراجع الانتقال. |
| `DELIVERED` | `delivered_at`؛ **إصدار الفاتورة** تلقائيًا في نفس المعاملة (فشل الترقيم = لا يُسجَّل التسليم). حالة الدفع لا تتغير. |
| `CANCELLED` | `cancelled_at` و`cancel_reason` (يُحفظ فقط عبر نقطتي الإلغاء، ولا يحفظه `PATCH fulfillment-status`)؛ **تحرير الحجوزات** (`qty_reserved` يرجع بلا حركة مخزون). |
| `RETURNED_TO_SELLER` | يحاول تحرير الحجوزات `HELD` فقط؛ بعد الشحن تكون `COMMITTED` فلا يحدث شيء، فلا يعود أي مخزون تلقائيًا. |

- **`cancellable` المُعاد في الاستجابة:** يُشتق من **جدول الانتقالات نفسه** الذي تفرضه عملية الإلغاء: `CustomerOrder.isCancellable() = OrderStatusMachine.isAllowed(status, CANCELLED)`، فلا يمكن أن يختلفا (كانت قاعدتان منفصلتان انحرفتا: العلم يعتمد `isDispatched()` ولا يعدّ الحالات المرتجعة). الإلغاء مسموح من أربع حالات فقط: `PENDING`, `CONFIRMED`, `AWAITING_SCHEDULE`, `PROCESSING`. ويبقى `false` لكل ما عداها: `SHIPPED`, `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY`, `RETURNED_TO_SELLER`, `CANCELLED`, `RETURNED`, `PARTIALLY_RETURNED`. إلغاء العميل لطلب غير قابل للإلغاء (ومنه المرتجع) يعيد الآن `ORDER_CANNOT_BE_CANCELLED` (409) في كل الحالات (كان يعيد `INVALID_STATUS_TRANSITION` للحالات المرتجعة الثلاث). اختبار `OrderCancellabilityTest` يمرّ على كل قيم الـ enum الثلاث عشرة ويقارن العلم بالعملية.
- التعليق على `DELIVERY_FAILED` في الـ enum يقول إنها "تحمل عدّاد محاولات"؛ **لا يوجد عدّاد محاولات في الكود** (فقط الملاحظة في سجل الحالات).
- الجدولة: انظر 3.11.

المصدر: `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:29-108,128-138` · `src/main/java/com/velora/api/order/domain/FulfillmentStatus.java` · `src/main/java/com/velora/api/order/service/OrderService.java:131-208,327-332,344-353,334-342,284-287,296-310,313-319` · `src/main/java/com/velora/api/order/domain/CustomerOrder.java:259-261` · `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:119-122` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:162-201,152-155` · `src/main/java/com/velora/api/order/web/AdminOrderController.java:88-95,66-73,135-142` · `src/main/java/com/velora/api/order/web/OrderController.java:67-74` · `src/main/java/com/velora/api/order/dto/CancelOrderRequest.java`

### 3.2 حالة الدفع (`PaymentStatus`) — 7 حالات

```
PENDING ─► AUTHORIZED ─► PAID ─► PARTIALLY_REFUNDED ─► REFUNDED
   │            │          └────────────────────────────► REFUNDED
   ├────────────┴─► FAILED ─► PENDING (إعادة)
   └────────────┴─► EXPIRED ─► PENDING (إعادة)
```

| من | إلى |
|---|---|
| `PENDING` | `AUTHORIZED`, `PAID`, `FAILED`, `EXPIRED` |
| `AUTHORIZED` | `PAID`, `FAILED`, `EXPIRED` |
| `PAID` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `REFUNDED` |
| `FAILED`, `EXPIRED` | `PENDING` |
| `REFUNDED` | — (نهائية) |

- **من يُطلقها (طريقان):**
  1. يدويًا: `PATCH /api/v1/admin/orders/{orderId}/payment-status?status=...&note=...` (معاملان في الـ query لا جسم JSON). يمر بآلة الحالات ويكتب صفًا في `order_status_history` بنوع `PAYMENT`. **لا يوجد أي شرط على حالة التنفيذ**: يمكن وضع `PAID` على طلب `PENDING` للتنفيذ.
  2. تسوية COD (3.5): تضع `PAID` على الطلبات `DELIVERED`+`COD`+`PENDING` **دون المرور بآلة الحالات ودون صف في `order_status_history`** (يُسجَّل في `audit_log`). وإلغاء التسوية يعيدها `PENDING` **بغض النظر عن حالتها الحالية** (حتى لو كانت قد أصبحت `REFUNDED` يدويًا).
- **لا أثر مخزون أو فاتورة أو بريد** لأي انتقال دفع. `AUTHORIZED/FAILED/EXPIRED` لا يولّدها أي كود (لا بوابة دفع)؛ وتعليق الـ enum الذي يقول إن `EXPIRED` يحرر الحجز غير منفَّذ.
- **لا يوجد مبلغ استرداد:** `PARTIALLY_REFUNDED`/`REFUNDED` مجرد تسميات؛ لا كيان ولا حساب للاسترداد.

المصدر: `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:140-151` · `src/main/java/com/velora/api/order/domain/PaymentStatus.java` · `src/main/java/com/velora/api/order/service/OrderService.java:263-280` · `src/main/java/com/velora/api/order/web/AdminOrderController.java:104-113` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:172,220`

### 3.3 حالة الطلب الخاص (`CustomRequestStatus`) — 7 حالات

```
NEW ─► CONTACTED ─┐
  │         │      ├─(PATCH /quote)─► QUOTED ─(PATCH /status)─► ACCEPTED ─(PATCH /status)─► COMPLETED
  └─(PATCH /quote)────┘            ▲  │
                                   └──┘ (تعديل السعر وهو QUOTED يبقيه QUOTED)
NEW | CONTACTED | QUOTED ─(PATCH /status + note)─► REJECTED
CONVERTED: معرّفة في الـ enum وعمود القاعدة، ولا يمكن الوصول إليها
```

| من | بالـ `PATCH /status` | بالـ `PATCH /quote` |
|---|---|---|
| `NEW` | `CONTACTED`, `REJECTED` | → `QUOTED` |
| `CONTACTED` | `REJECTED` | → `QUOTED` |
| `QUOTED` | `ACCEPTED`, `REJECTED` | يبقى `QUOTED` (سعر معدَّل) |
| `ACCEPTED` | `COMPLETED` | مرفوض |
| `REJECTED`, `CONVERTED`, `COMPLETED` | — | مرفوض |

- `QUOTED` **لا يُضبط يدويًا** (يرفضه `/status`)، بل يأتي فقط من إعطاء سعر؛ لذلك لا يمكن أن توجد حالة `QUOTED` بلا سعر. `ACCEPTED` يتطلب سعرًا مسجَّلًا (`CUSTOM_REQUEST_QUOTE_REQUIRED` 409 إن لم يوجد). `REJECTED` تتطلب `note` (وإلا `VALIDATION_FAILED`). `CONVERTED` يُرفض دائمًا (التحويل إلى طلب غير مبني).
- الدالة `CustomRequestStatus.isTerminal()` تعدّ `ACCEPTED` نهائية رغم وجود `ACCEPTED → COMPLETED`؛ وتُستعمل فقط لتحديد رسالة الخطأ.
- **الآثار:** كل تغيير حالة وكل تغيير سعر يُسجَّل في `audit_log` (`CUSTOM_REQUEST_STATUS_CHANGED` / `CUSTOM_REQUEST_QUOTED`) مع المنفِّذ؛ الصف يحتفظ بآخر ملاحظة وآخر سعر فقط (`adminNote`, `quotedAmount`). الصف يُقفل (`lockById`) أثناء التعديل. **لا بريد ولا إشعار للعميل ولا حجز مخزون ولا إنشاء طلب** في أي انتقال. المرفقات تُقبل فقط وهو `NEW` (`CUSTOM_REQUEST_CLOSED` 409 بعد ذلك).
- عرض السعر: `amount` بين 0.01 و100,000,000، **للطلب كله (كل الوحدات) وشامل الضريبة**.
- **تحذير نشر:** الحالة `COMPLETED` تتطلب تطبيق السكربت `V18__custom_request_completed.sql` على القاعدة. ملف `src/main/resources/db/README.md` يقول إنه "Not yet applied to `royal`". إن لم يُطبَّق، فتعيين `COMPLETED` يفشل بقيد CHECK، وسيعيده `GlobalExceptionHandler` كـ `DUPLICATE_VALUE` (409 "This value is already in use") لأن رسالة قيد CHECK لا تطابق أي حالة معروفة. (لم أتحقق من حالة قاعدة البيانات الحية.)

المصدر: `src/main/java/com/velora/api/customrequest/domain/CustomRequestStatus.java` · `src/main/java/com/velora/api/customrequest/service/CustomRequestAdminService.java:108-137,148-181,185-196` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:233-283` · `src/main/java/com/velora/api/customrequest/dto/CustomRequestQuoteRequest.java` · `src/main/resources/db/V18__custom_request_completed.sql` · `src/main/resources/db/README.md:17` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:168-192,195-217`

### 3.4 حالة الفاتورة (`InvoiceStatus`)

```
(لا فاتورة) ──DELIVERED──► ISSUED ──POST /admin/invoices/{id}/cancel (reason)──► CANCELLED (نهائية)
```

- الإصدار: تلقائي عند `DELIVERED`؛ أو يدويًا `POST /api/v1/admin/invoices/issue/{orderId}` (idempotent، لطلب `DELIVERED` فقط).
- الإلغاء: يتطلب `reason` (غير فارغ، حتى 255). الرقم يبقى مستهلكًا والصف يبقى للأبد. **يُكتب سجل تدقيق** `INVOICE_CANCELLED` (الرقم، السبب، المنفِّذ).
- **إعادة الإصدار بعد الإلغاء:** تُصدَر فاتورة **جديدة** بالرقم التالي في التسلسل وتشير إلى الملغاة (`replaces_invoice_id`، ويظهر `replacesInvoiceNumber` في ردّ الأدمن)، عبر `POST /admin/invoices/issue/{orderId}` للطلب `DELIVERED`. للطلب فاتورة حيّة واحدة على الأكثر (فهرس مرشَّح `uq_invoice_order` على غير الملغاة) وأي عدد من الملغاة؛ الملغاة تبقى برقمها فلا فجوة ولا إعادة استعمال. الإعادة تُسجَّل `INVOICE_REISSUED`. في ردّ الطلب يظهر `invoiceNumber` للفاتورة الحيّة فقط؛ ولكي لا يبدو الطلب «بلا فاتورة» يضيف ردّ الأدمن `hasCancelledInvoices: true` (يغيب إن لم توجد ملغاة) ويعرض `GET /admin/invoices?orderId=` كل فواتير الطلب بحالاتها. لا إشعارات دائنة (انظر 1.6). الـ PDF الملغى يبقى قابلًا للتنزيل (للعميل ولـ الأدمن) ويحمل علامة «فاتورة ملغاة» (`cancelled` في `InvoiceView`).

المصدر: `src/main/java/com/velora/api/invoice/domain/InvoiceStatus.java` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:113-191,222-246` · `src/main/java/com/velora/api/invoice/repository/InvoiceRepository.java:20` · `src/main/java/com/velora/api/invoice/web/AdminInvoiceController.java:85-88,97-102` · `src/main/java/com/velora/api/invoice/dto/CancelInvoiceRequest.java` · `src/main/resources/db/V19__replaceable_invoice_and_settlement.sql:87-89`

### 3.5 حالة التسوية (`RemittanceStatus`)

```
(طلبات DELIVERED+COD+PENDING) ──POST /admin/remittances──► SETTLED   (received ≥ expected)
                                                     └────► SHORT     (received < expected)
SETTLED | SHORT ──POST /admin/remittances/{id}/cancel?reason=──► CANCELLED (نهائية)
```

- الإنشاء يتحقق من **كل** طلب قبل كتابة أي شيء (`validateSettleable`): `COD`، `DELIVERED`، `paymentStatus = PENDING`. غير ذلك `VALIDATION_FAILED` (400).
- `expected` = مجموع `grandTotal` للطلبات؛ `difference = received − expected`. الفرق ≠ 0 **يتطلب `note`**. الفرق السالب → `SHORT`؛ الصفر **أو الموجب (زيادة)** → `SETTLED`.
- الأثر: كل طلب يصير `paymentStatus = PAID` **حتى لو كانت التسوية `SHORT`**، وتُنسخ قيمته في بند التسوية (`amount`)، وتُسجَّل الحركة في `audit_log` (`PAYMENT_ADJUSTED`). مرجع التسوية `REM-<السنة>-<NNNN>`.
- لا توجد حالة لإنهاء `SHORT` (لا تُسدَّد لاحقًا ولا تتحول إلى `SETTLED`): تبقى `SHORT` حتى تُلغى.
- الإلغاء: يتطلب `reason` (معامل query) غير فارغ؛ يعيد كل طلباتها `PENDING`، ويُضاف السبب للملاحظة ويُسجَّل `PAYMENT_ADJUSTED`، ويضع بنودها `is_active = 0` (تبقى كسجل). **`CANCELLED` نهائية صراحةً:** `RemittanceStatus.isTerminal()` و`canBeCancelled()`؛ لا مسار في الكود يخرج منها أو يعيد فتحها (فلا خطر أن تتصادم إعادة تفعيل بنودها مع تسوية أحدث للطلب نفسه). إلغاؤها مرتين `INVALID_STATUS_TRANSITION`.
- **إعادة التسوية بعد الإلغاء (أُصلح):** فهرس `uq_remittance_item_order` صار فريدًا على `order_id` **للبنود الفعّالة فقط** (`WHERE is_active = 1`، V19) فيدخل الطلب الذي أُلغيت تسويته في تسوية جديدة بسلاسة. الطلب في تسوية **فعّالة** واحدة على الأكثر وفي أي عدد من الملغاة. مرجع التسوية (`REM-<السنة>-<NNNN>`) يُحسب من أعلى رقم موجود بما فيه الملغاة فلا يُعاد رقم. ترحيل V19 يعيّن `is_active = 0` لبنود التسويات الملغاة الموجودة أصلًا.

المصدر: `src/main/java/com/velora/api/remittance/domain/RemittanceStatus.java` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:104-195,204-240,260-276` · `src/main/java/com/velora/api/remittance/domain/CodRemittanceItem.java:53` · `src/main/java/com/velora/api/remittance/web/AdminRemittanceController.java:98-103` · `src/main/resources/db/V19__replaceable_invoice_and_settlement.sql:121-123`

### 3.6 أنواع حركة المخزون (`MovementType`)

سجل `stock_movement` دفتر إضافة فقط (append-only): `quantityDelta` (موقّعة)، `qtyAfter`، `referenceType`/`referenceId`، `reason`، المنفِّذ (id + الاسم منسوخًا).

| النوع | يكتبه الكود؟ | متى |
|---|---|---|
| `PURCHASE_RECEIVED` | نعم | `POST /admin/inventory/{variantId}/receive` (reference `PURCHASE`)؛ الرصيد الافتتاحي عند إنشاء متغيّر بـ `initialStock > 0` (reference `VARIANT_CREATE`)؛ أو يدويًا عبر `adjust` بنوع `PURCHASE_RECEIVED`. |
| `SALE` | نعم | عند `SHIPPED` لكل حجز (reference `ORDER`) — سالبة. لا يمكن إدخاله يدويًا. |
| `MANUAL_ADJUSTMENT` | نعم | `POST /admin/inventory/{variantId}/adjust` (الافتراضي). reference `ADJUSTMENT`. |
| `DAMAGE_WRITEOFF` | نعم | `adjust` بنوعه يدويًا. |
| `CANCELLATION_RESTOCK`, `RETURN_SELLABLE`, `RETURN_DAMAGED` | **لا** | معرّفة ومحظورة يدويًا (`MOVEMENT_TYPE_NOT_MANUAL`)، ولا شيء في الكود يكتبها: لا إعادة تخزين تلقائية للمرتجعات ولا للإلغاء. |

- `adjust` يقبل أي نوع غير المحظور؛ أي أن `PURCHASE_RECEIVED` متاح لها أيضًا. سالب `PURCHASE_RECEIVED` ممكن تقنيًا عبر `adjust` (لا فحص إشارة للنوع).
- `qty_reserved` لا يُوثَّق بحركات.

المصدر: `src/main/java/com/velora/api/inventory/domain/MovementType.java` · `src/main/java/com/velora/api/inventory/service/InventoryAdminService.java:73-77,87-92,212-231,149-210` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:267-296` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:162-201`

### 3.7 حالة حجز المخزون (`ReservationStatus`)

```
HELD ──(SHIPPED)──► COMMITTED
HELD ──(CANCELLED / RETURNED_TO_SELLER قبل التثبيت)──► RELEASED
HELD (بلا orderId, انتهت مدتها) ──(Job كل دقيقة)──► EXPIRED
```

- يُنشأ `HELD` في `placeOrder` ويُربط بالطلب في نفس المعاملة؛ فعمليًا `EXPIRED` لا يحدث في مسار الشراء الحالي (انظر 2.8). `releaseForCart` معرّفة ولا يستدعيها أحد.

المصدر: `src/main/java/com/velora/api/inventory/domain/ReservationStatus.java` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:228-238,145-148` · `src/main/java/com/velora/api/inventory/repository/StockReservationRepository.java:38-39`

### 3.8 حالة السلة (`CartStatus`)

```
ACTIVE ──(تسجيل الدخول + POST /cart/merge)──► MERGED
ACTIVE ──(placeOrder)──► CONVERTED
ACTIVE ──?──► ABANDONED   (معرّفة؛ لا شيء في الكود يضعها)
```

- سلة نشطة واحدة لكل مستخدم (`ux_cart_user`) وواحدة لكل رمز ضيف. أقصى 50 سطرًا مختلفًا في السلة (`VALIDATION_FAILED`). لا يوجد انتهاء صلاحية لسلة الضيف ولا Job تنظيف.
- دمج سلة الضيف: الكميات تُجمع وتُقصّ على المتاح؛ الأسطر غير القابلة للبيع تُهمل بصمت ولا يُبلَّغ عن الاقتطاع في الاستجابة (رغم تعليق الكود "the cap is reported" — لا يُضاف تحذير).

المصدر: `src/main/java/com/velora/api/cart/domain/CartStatus.java` · `src/main/java/com/velora/api/cart/service/CartService.java:172-225,255-261,49` · `src/main/resources/db/baseline/baseline_through_V14.sql:1501-1505`

### 3.9 حالات المنتج والمتغيّر والمستخدم والمعرض

**المنتج (`ProductStatus`: `DRAFT, ACTIVE, ARCHIVED`):**
```
(إنشاء) DRAFT ──publish──► ACTIVE ──unpublish──► DRAFT
ACTIVE|DRAFT ──archive──► ARCHIVED ──publish──► ACTIVE   (publish يمسح archivedAt)
```
- `publish` يتطلب ترجمة `ar` موجودة (`PRODUCT_MISSING_ARABIC_NAME`)، ولمنتج `READY_MADE` متغيّرًا واحدًا على الأقل (`PRODUCT_HAS_NO_VARIANTS`). `unpublish` لا يمسح `archivedAt` (يبقى `archivedAt` مضبوطًا إن سُوِّد منتج مؤرشف). الأرشفة لا تُسجَّل في التدقيق (`PRODUCT_PUBLISHED`/`PRODUCT_ARCHIVED` معرّفة بلا استخدام).
- ما يراه العميل: المنتج `ACTIVE` وغير مؤرشف فقط (البحث وصفحة `/products/{slug}`)؛ مسودة = كأنها غير موجودة (`PRODUCT_NOT_FOUND` 404).
- **المتغيّر (`VariantStatus`: `ACTIVE, INACTIVE, ARCHIVED`):** `ACTIVE` عند الإنشاء، `ARCHIVED` عند `DELETE /admin/variants/{id}`. `INACTIVE` لا يضعه أي كود. لا يوجد "إلغاء أرشفة". القابل للبيع: متغيّر `ACTIVE` وغير مؤرشف + منتج `ACTIVE` غير مؤرشف + `READY_MADE`.
- **المستخدم (`UserStatus`: `ACTIVE, SUSPENDED, DELETED`):** لا يوجد endpoint يغيّر الحالة. تُقرأ فقط (`isActive` عند الدخول والتجديد → `ACCOUNT_SUSPENDED` 403). لا حذف حساب.
- **المعرض:** ثلاث حالات مشتقة من `published` و`archivedAt`: `DRAFT` (عند الإنشاء) ⇄ `LIVE` عبر `PATCH /publish {published:true|false}` (صريح لا toggle)؛ `archive` (يُلغي النشر ويضع `archivedAt`) ثم `restore` (يعود **مسودة**، لا منشورًا). نشر عنصر مؤرشف مرفوض (`PORTFOLIO_ITEM_ARCHIVED` 409). كل تغيير فعلي يُسجَّل `PORTFOLIO_STATUS_CHANGED`، وتكرار نفس الحالة لا يُسجَّل.

المصدر: `src/main/java/com/velora/api/catalog/domain/ProductStatus.java` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:157-173,176-180,187-193` · `src/main/java/com/velora/api/catalog/service/ProductQueryService.java:76-89,138-141` · `src/main/java/com/velora/api/catalog/domain/VariantStatus.java` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:244-254` · `src/main/java/com/velora/api/identity/domain/UserStatus.java` · `src/main/java/com/velora/api/identity/domain/AppUser.java:85-87` · `src/main/java/com/velora/api/portfolio/service/PortfolioAdminService.java:120-132,136-147,151-161,165-170`

### 3.10 حالة مفتاح Idempotency (`IdempotencyStatus`)

```
(مفتاح جديد) ──► IN_PROGRESS ──► COMPLETED   (إعادة الرد المخزَّن عند التكرار)
                     └────────► FAILED       (المفتاح مستهلك: التكرار يُرفض)
```
التفاصيل والأكواد في 2.6.

المصدر: `src/main/java/com/velora/api/idempotency/domain/IdempotencyStatus.java` · `src/main/java/com/velora/api/idempotency/service/IdempotencyService.java:61-88`

### 3.11 موعد التسليم (التفضيل والموعد المتفق)

ليست آلة حالة مستقلة بل حقول على الطلب بحياة خاصة:

- **`preferredDeliveryDate` + `preferredDeliverySlot`** (`MORNING|AFTERNOON|EVENING`): يرسلهما العميل عند الـ checkout، "رغبة لا وعد". التحقق: الفترة بلا تاريخ مرفوضة؛ التاريخ ليس في الماضي (بتوقيت **القاهرة**) ولا بعد 90 يومًا (`VALIDATION_FAILED`). لا يُقارَن بأيام التوصيل المتوقعة للمنطقة عمدًا.
- **`scheduledDeliveryAt`** (`OffsetDateTime`): الموعد الذي يتفق عليه الموظف. `PATCH /api/v1/admin/orders/{orderId}/schedule` (`scheduledDeliveryAt` + `note` اختياري ≤500). مسموح فقط في: `CONFIRMED, AWAITING_SCHEDULE, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERY_FAILED` (غير ذلك `ORDER_NOT_SCHEDULABLE` 409). يجب أن يكون في المستقبل (`VALIDATION_FAILED`). **لا يغيّر حالة الطلب.** إعادة حفظ نفس اللحظة لا تغيّر ولا تُسجَّل؛ كل تغيير فعلي يُسجَّل في `audit_log` (`ORDER_DELIVERY_SCHEDULED`) بالقديم والجديد بتوقيت القاهرة. يُقرأ كشرط للانتقال `AWAITING_SCHEDULE → PROCESSING`.

المصدر: `src/main/java/com/velora/api/order/service/CheckoutService.java:249-268,74` · `src/main/java/com/velora/api/order/domain/DeliverySlot.java` · `src/main/java/com/velora/api/order/service/OrderService.java:223-254` · `src/main/java/com/velora/api/order/domain/FulfillmentStatus.java:70-73` · `src/main/java/com/velora/api/order/web/AdminOrderController.java:124-131` · `src/main/java/com/velora/api/order/dto/ScheduleDeliveryRequest.java`

### 3.12 حالات أخرى

- **رمز OTP:** صالح 10 دقائق، 5 محاولات خاطئة كحد أقصى (بعدها `OTP_TOO_MANY_ATTEMPTS`)، رمز جديد يلغي السابق لنفس الغرض، 5 طلبات/ساعة لكل وجهة (مضبوطة بالكود) + 10 طلبات/ساعة لكل IP (من الإعداد). نجاح التحقق يضع `phoneVerifiedAt`/`emailVerifiedAt`. **الرمز لا يُرسَل بأي قناة** (مكتوب `TODO(notification module)`)، ولا يُسجَّل في اللوغ.
- **Refresh token:** `usable = غير منتهٍ وغير مُلغى`؛ صلاحيته 30 يومًا؛ يدور (rotation) عند كل تجديد؛ `logout-all` وإعادة كلمة المرور تُلغي الكل.
- **رمز إعادة تعيين كلمة المرور:** صالح ساعة، استعمال واحد، إصدار رمز جديد يبطل السابق. **لا يُسلَّم للمستخدم** (`startPasswordReset` يعيد الرمز الخام ولا يرسله إلى أي قناة؛ الـ controller يرمي به). أي أن إعادة التعيين غير قابلة للاستعمال فعليًا اليوم.
- **توقيعات:** رمز الضيف (`<uuid>.<HMAC>`) بلا انتهاء؛ رمز مرفقات الطلب الخاص (`<requestId>.<expiryEpochSec>.<HMAC>`) صالح 24 ساعة ومربوط بطلب واحد.

المصدر: `src/main/java/com/velora/api/identity/service/OtpService.java:68-108,111-136,38,37` · `src/main/java/com/velora/api/identity/domain/OtpVerification.java:34` · `src/main/java/com/velora/api/identity/service/AuthService.java:173-192,223-245,248-266` · `src/main/java/com/velora/api/identity/web/AuthController.java:131-134,111-117` · `src/main/java/com/velora/api/customrequest/service/CustomRequestTokenService.java` · `src/main/java/com/velora/api/cart/security/GuestTokenService.java:64-67`

## 4. نقاط النهاية (Endpoints)

### 4.0 اصطلاحات عامة

- المسار الأساسي `/api/v1`. الاستجابات JSON بحقول `camelCase` وتحذف `null` (2.5). الأوقات `OffsetDateTime` ISO-8601 (UTC عادةً)، والتواريخ `LocalDate`.
- **اللغة:** الرأس `Accept-Language` فقط (`ar` أو `en`؛ أي قيمة أخرى أو غياب = `ar`). لا يوجد معامل `lang`.
- **المصادقة:** `Authorization: Bearer <accessToken>` (HS512، صلاحية 30 دقيقة). **رمز الضيف:** `X-Guest-Token` يُصدر من `POST /cart/guest-token`. **الطلب الخاص:** `X-Request-Token`. **Idempotency:** `Idempotency-Key`.
- **شكل الخطأ (RFC 7807):** `{ type: "/errors/<code-kebab>", title, status, detail, instance, code, timestamp, errors?: [{field, message}] }`. العميل يتفرّع على `code` لا على `detail` (الرسائل بالإنجليزية). أخطاء التحقق من الحقول `VALIDATION_FAILED` 400 مع `errors[]`. JSON معطوب أو بنوع خاطئ `INVALID_REQUEST_BODY` 400. معامل بقيمة خاطئة/ناقص `INVALID_PARAMETER` 400. أي استثناء غير متوقع `INTERNAL_ERROR` 500 بلا تفاصيل. انتهاك قيد قاعدة البيانات غير المتوقع: فريد → `DUPLICATE_VALUE` 409 (أو كود أدق لبعض القيود المعروفة كـ`SKU_ALREADY_EXISTS`)، NOT NULL → `REQUIRED_FIELD_MISSING` 400، مفتاح أجنبي مفقود → `INVALID_REFERENCE` 400، مفتاح أجنبي يمنع الحذف → `REFERENCED_BY_OTHER_RECORDS` 409.
- **الترقيم:** `PageResponse` = `{content, page (يبدأ من 0), size, totalElements, totalPages, first, last, empty}`. معاملات `page` و`size` من Spring. الأحجام الافتراضية مذكورة لكل endpoint.
- **أكواد HTTP الناجحة:** 201 لعمليات الإنشاء عبر POST (مذكورة)، 204 للحذف (المذكور)، 200 لما عداها.
- **401 مقابل 403:** طلب إلى endpoint محمي بلا رمز، أو برمز منتهٍ أو تالف، يعيد `401 UNAUTHORIZED` بجسم الخطأ القياسي (`type, title, status, code, detail, instance, timestamp`). مستخدم مسجَّل بلا الدور (عميل على مسار أدمن) يعيد `403 FORBIDDEN` بالجسم نفسه. هذا الفصل تنفّذه `SecurityConfig` (`authenticationEntryPoint` و`accessDeniedHandler`)؛ كان الخادم يعيد 403 للحالتين فيضطر العميل لمعاملة كل 403 كجلسة منتهية. المسارات العامة لا تتأثر حتى لو أُرسل رمز منتهٍ.
- **أي مستخدم مسجَّل (حتى الأدمن) يستطيع استدعاء `/me/*`**؛ و`/admin/**` يتطلب دور `ADMIN` فقط (لا صلاحيات تفصيلية).

المصدر: `src/main/java/com/velora/api/common/security/SecurityConfig.java:85-128,47-61,64-73,131-145` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:60-65,70-80,168-192,282-292` · `src/main/java/com/velora/api/catalog/web/LocaleResolver.java:21-29` · `src/main/java/com/velora/api/common/dto/PageResponse.java` · `src/main/java/com/velora/api/identity/security/JwtService.java:68-82`

### 4.1 نقاط عامة (بلا مصادقة)

**الهوية**

| الطلب | الغرض | المدخلات (* إلزامي) | ما يتغير | الاستجابة | الإخفاق |
|---|---|---|---|---|---|
| `POST /auth/register` | تسجيل عميل | `firstName*`(≤100) `lastName` `phone` `email` `password*`(8–72) `locale` — يلزم `phone` أو `email` | ينشئ `app_user` بدور `CUSTOMER` + refresh token | 201 `AuthResponse{accessToken, refreshToken, tokenType:"Bearer", expiresIn(ثوانٍ), user}` | `VALIDATION_FAILED`, `INVALID_PHONE_FORMAT` 400, `PHONE_ALREADY_EXISTS`/`EMAIL_ALREADY_EXISTS` 409. لا حد معدل؛ لا يشترط OTP. |
| `POST /auth/login` | دخول | `identifier*`(هاتف أو بريد) `password*` `deviceInfo` | `last_login_at`، refresh token جديد | `AuthResponse` | `RATE_LIMITED` 429، `INVALID_CREDENTIALS` 401 (نفس الخطأ لحساب غير موجود/كلمة خاطئة)، `ACCOUNT_SUSPENDED` 403 (بعد التحقق من كلمة المرور) |
| `POST /auth/refresh` | تجديد | `refreshToken*` | يُلغي القديم ويصدر جديدًا (rotation) | `AuthResponse` | `TOKEN_INVALID` 401 (غير معروف)، `TOKEN_EXPIRED` 401 (منتهٍ **أو مُلغى سلفًا**)، `ACCOUNT_SUSPENDED` 403 |
| `POST /auth/logout` | خروج من هذا الجهاز | `refreshToken*` | يُلغي الرمز إن وُجد | `{message}` | لا يفشل لرمز مجهول |
| `POST /auth/otp/send` | إصدار رمز تحقق | `destination*` `purpose*`(`REGISTER\|LOGIN\|RESET\|CHANGE_PHONE`) | يخزّن رمزًا مُجزّأً BCrypt ويُبطل السابق | `{message}` — **لا يعيد الرمز ولا يرسله** | `RATE_LIMITED` 429 (10/ساعة/IP)، `OTP_TOO_MANY_ATTEMPTS` 429 (5/ساعة/وجهة)، `INVALID_PHONE_FORMAT` |
| `POST /auth/otp/verify` | تحقق | `destination*` `code*`(6 أرقام) `purpose*` | يستهلك الرمز؛ يضع `phoneVerifiedAt`/`emailVerifiedAt` إن وُجد مستخدم | `{message}` | `OTP_INVALID` 400، `OTP_EXPIRED` 400، `OTP_TOO_MANY_ATTEMPTS` 429 |
| `POST /auth/password/forgot` | بدء إعادة تعيين | `identifier*` | يُبطل رموز المستخدم السابقة ويصدر رمزًا (ساعة) | `{message}` دائمًا نجاح — **لا يُرسل الرمز** | — |
| `POST /auth/password/reset` | إكمال | `token*` `newPassword*`(8–72) | يغيّر كلمة المرور ويُلغي كل refresh tokens | `{message}` | `TOKEN_INVALID` 401, `TOKEN_EXPIRED` 401 |

**الكتالوج (GET فقط، بدون مصادقة)**

| الطلب | الغرض | المدخلات | الاستجابة | ملاحظات/الإخفاق |
|---|---|---|---|---|
| `GET /products` | بحث وتصفية | اختيارية كلها: `q`, `categoryId`, `brandIds[]`, `minPrice`, `maxPrice`, `attributeValueIds[]`, `inStockOnly`, `featured`, `newArrival`, `fulfillmentType`, `sort`(`newest\|price_asc\|price_desc\|name`، غيرها = `newest`), `page`, `size` | `PageResponse<ProductSummaryResponse>` | الافتراضي 24، **الحد 60 (يُقتطع بصمت)**. فقط المنتجات `ACTIVE` غير المؤرشفة. `q` يُطبَّع بـ`ArabicNormalizer` ويُبحث في اسم+وصف قصير بلغة الطلب. ترتيب `name` فعليًا بالـ`slug`. |
| `GET /products/{slug}` | صفحة المنتج بكل المتغيّرات | — | `ProductDetailResponse` | `PRODUCT_NOT_FOUND` 404 لمسودة/مؤرشف/غير موجود |
| `GET /products/{id}/related` | منتجات مرتبطة | — | قائمة ≤8 (غير مرقّمة) | 404 إن لم يوجد أو أُرشف؛ **لا يتحقق من أنه `ACTIVE`** |
| `GET /products/featured` ، `GET /products/new-arrivals` | الرئيسية | `page`,`size` | `PageResponse` (افتراضي 12) | يعرض المتوفر فقط (`inStockOnly = true`) |
| `GET /categories/tree` | شجرة التصنيفات | — | قائمة (غير مرقّمة) | — |
| `GET /categories/{slug}` | صفحة تصنيف | — | `CategoryDetailResponse` | — |
| `GET /categories/filters` | محاور الفلترة | `categoryId?` | `FilterFacetsResponse` | — |
| `GET /brands` | العلامات الفعّالة | — | قائمة مرتبة بـ`nameAr` | غير مرقّمة |
| `GET /variants/{id}/availability` | توفر متغيّر الآن | — | `{variantId, sku, price, availableQty, inStock, sellable}` | `VARIANT_NOT_FOUND` 404. **يكشف الكمية المتاحة الدقيقة للعامة.** |
| `GET /portfolio` ، `GET /portfolio/{slug}` | معرض الأعمال المنشورة | `categoryId?`, `page`,`size`(12) | `PageResponse<PortfolioSummaryResponse>` / `PortfolioDetailResponse` | 404 لغير منشور/مؤرشف/مجهول |
| `GET /portfolio/categories` | تصنيفات المعرض (فلتر الصفحة) | — | قائمة مسطّحة `[{id,slug,name,imageUrl,displayOrder}]` | تصنيفات `PORTFOLIO` الفعّالة فقط. عنصر بلا تصنيف يظهر في القائمة غير المفلترة وحدها. `categories` **slug محجوز** لعناصر المعرض (`SLUG_RESERVED` 400 عند الإنشاء/التعديل برسالة تشرح السبب)؛ والـ slug المولَّد من العنوان إن جاء `categories` يأخذ `categories-2`. أي مسار ثابت يُضاف تحت `/portfolio` يجب إضافته إلى `PortfolioAdminService.RESERVED_SLUGS` وإلا فشل اختبار `PortfolioReservedSlugIntegrationTest` |
| `GET /geo/governorates` | المحافظات الـ27 وشروط الشحن | — | قائمة `GovernorateResponse{id, code, name, zoneName, shippingRates[{sizeClass, unitCost}], maxShippingCost, deliveryDaysMin/Max, served}` | غير مرقّمة؛ المحافظة غير المخدومة تعود `served:false` |
| `GET /ping` | حيوية | — | `{status:"ok", service:"velora-api", time}` | اسم الخدمة ثابت في الكود |
| `GET /uploads/**` | ملفات مرفوعة | — | الملف | كاش 30 يومًا. عام لـ`products/` و`categories/` و`portfolio/` و`custom-requests/` (الأخير قرار مقصود، 7.5). **`/uploads/invoices/**` مغلق** (401/403). **ملف غير موجود تحت `/uploads/` يعيد 500 لا 404** (7.5). |
| `/swagger-ui.html`, `/v3/api-docs`, `GET /actuator/health`, `/actuator/info` | توثيق/فحص | — | — | مسموحة في الأمان بلا مصادقة (لم أتحقق من أي `actuator` مُعرَّض فعلًا) |

**السلة والـ checkout (ضيف أو مسجَّل)** — الهوية: `Authorization` إن وُجد، وإلا `X-Guest-Token` (المسجَّل يتجاهل الرأس).

| الطلب | الغرض | المدخلات | ما يتغير | الاستجابة | الإخفاق |
|---|---|---|---|---|---|
| `POST /cart/guest-token` | إصدار رمز ضيف | — | لا شيء | `{guestToken}` | — |
| `GET /cart` | قراءة السلة | رأس الهوية | **ينشئ السلة إن لم توجد** | `CartResponse` | بلا هوية: `VALIDATION_FAILED` 400؛ توقيع خاطئ `TOKEN_INVALID` 401 |
| `POST /cart/items` | إضافة | `variantId*` `quantity*`(1–99) | يضيف أو يزيد (ويحدّث `priceAtAdd`) | `CartResponse` | `VARIANT_NOT_FOUND` 404، `PRODUCT_NOT_PURCHASABLE` 409 (ليس `READY_MADE`)، `PRODUCT_NOT_ACTIVE` 409، `STOCK_UNAVAILABLE` 409 (المجموع > المتاح)، `VALIDATION_FAILED` (أكثر من 50 سطرًا) |
| `PATCH /cart/items/{itemId}` | تغيير كمية | `quantity*`(1–99) | يعدّل | `CartResponse` | `CART_ITEM_NOT_FOUND` 404، `STOCK_UNAVAILABLE` 409 |
| `DELETE /cart/items/{itemId}` | حذف سطر | — | يحذف | `CartResponse` | `CART_ITEM_NOT_FOUND` 404 |
| `DELETE /cart` | تفريغ | — | يحذف الأسطر ويمسح `couponCode` | `CartResponse` | — |
| `POST /shipping/quote` | سعر الشحن | `governorateId*` `cartId?` | لا شيء | `ShippingQuoteResponse` | `RESOURCE_NOT_FOUND` 404 (محافظة)، `GOVERNORATE_NOT_SERVED` 409، `CART_EMPTY` 400 (لا سلة)، `SHIPPING_SIZE_MISSING`/`SHIPPING_RATE_NOT_CONFIGURED` 409. `cartId` صريح يجب أن يكون لسلة المتصل وإلا `RESOURCE_NOT_FOUND` 404 (مثل غير الموجودة). |
| `POST /orders` | تحويل السلة إلى طلب | رأس: `X-Guest-Token`, `Idempotency-Key`. جسم: `addressId` **أو** `address{recipientName*, phone*, altPhone, email, governorateId*, area, streetAddress*, building, floor, apartment, landmark}`، `paymentMethod`(COD فقط)، `customerNote`(≤500)، `preferredDeliveryDate`، `preferredDeliverySlot` | ينشئ الطلب والبنود والحجوزات ويحوّل السلة ويكتب سجل الحالة ويرسل حدثًا للبريد | 201 `OrderResponse` (للضيف يُبنى بنفس شكل ردّ الأدمن) | `CART_EMPTY` 400؛ `STOCK_UNAVAILABLE` 409 (يشمل كل تحذيرات السلة عدا تغيّر السعر: نفاد/اقتطاع كمية/منتج لم يعد للبيع)؛ `INVALID_ADDRESS` 400؛ `INVALID_PHONE_FORMAT` 400؛ `UNAUTHORIZED` 401 (`addressId` بلا تسجيل)؛ `RESOURCE_NOT_FOUND` 404 (عنوان ليس لك/محافظة)؛ `GOVERNORATE_NOT_SERVED`, `SHIPPING_SIZE_MISSING`, `SHIPPING_RATE_NOT_CONFIGURED`, `PAYMENT_METHOD_UNAVAILABLE`, `DUPLICATE_ORDER` 409؛ `VALIDATION_FAILED` (تاريخ التسليم) |

- **تغيّر السعر لا يمنع الشراء:** `PRICE_CHANGED` تحذير في السلة فقط؛ الطلب يُنشأ بالسعر الحالي دون إيقاف. الـ checkout يتجاهل `priceAtAdd`.
- **لا حد معدل على `POST /orders`**. حدود: `quantity` 1–99 للسطر، 50 سطرًا للسلة.
- **`POST /cart/merge` يتطلب مصادقة فعلًا:** قاعدة صريحة في `SecurityConfig` قبل القاعدة العامة للسلة؛ بلا تسجيل `401 UNAUTHORIZED` (كان يصل إلى المعالج فيرمي `NullPointerException` ويعيد 500).

**الطلبات الخاصة**

| الطلب | الغرض | المدخلات | ما يتغير | الاستجابة | الإخفاق |
|---|---|---|---|---|---|
| `POST /custom-requests` | طلب مقاس/صنع/عمل مخصص | `type*`(`SIZE_VARIANT\|MADE_TO_ORDER\|CUSTOM_WORK`) `productId`(إلزامي لـSIZE_VARIANT) `contactName*`(≤150) `phone*` `altPhone` `email` `governorateId*` `area` `streetAddress` `widthCm/heightCm/depthCm`(>0، ≤10000؛ SIZE_VARIANT يلزمه بعد واحد) `quantity`(1–10000، الافتراضي 1) `notes`(≤1000) | ينشئ طلبًا `NEW` برقم `REQ-…`، يربطه بالحساب إن كان مسجَّلًا | 201 `{id, requestNumber, status, attachmentToken, attachmentTokenExpiresAt, createdAt}` — الرمز يظهر مرة واحدة | `RATE_LIMITED` 429 (10/ساعة/IP)، `INVALID_PHONE_FORMAT`، `RESOURCE_NOT_FOUND` 404 (محافظة)، `PRODUCT_NOT_FOUND` 404 (غير فعّال/مؤرشف)، `VALIDATION_FAILED` |
| `POST /custom-requests/{id}/attachments` | صورة مرجعية | `multipart`: `file*`؛ رأس `X-Request-Token` (أو صاحب الطلب المسجَّل) | يخزّن الصورة | 201 `{id, url, contentType, sizeBytes, createdAt}` | `RATE_LIMITED` 429 (30/ساعة/IP)، `CUSTOM_REQUEST_NOT_FOUND` 404 (غير موجود أو رمز خاطئ/منتهٍ/لطلب آخر)، `CUSTOM_REQUEST_CLOSED` 409 (الحالة ≠ NEW)، `VALIDATION_FAILED` (أكثر من 5 صور أو ملف غير صالح)، `FILE_REQUIRED` 400 |

المصدر: `src/main/java/com/velora/api/identity/web/AuthController.java:57-59,70-73,79-81,85-88,111-117,121-124,131-134,140-143` · `src/main/java/com/velora/api/identity/service/AuthService.java:85-119,131-163` · `src/main/java/com/velora/api/identity/service/OtpService.java:68-108,111-136` · `src/main/java/com/velora/api/catalog/web/ProductController.java:43-49,59-64,69-72,77-82,86-91` · `src/main/java/com/velora/api/catalog/service/ProductQueryService.java:125-136,35,103-107,109-113` · `src/main/java/com/velora/api/catalog/web/CategoryController.java` · `src/main/java/com/velora/api/catalog/web/BrandController.java` · `src/main/java/com/velora/api/catalog/web/VariantController.java` · `src/main/java/com/velora/api/portfolio/web/PortfolioController.java` · `src/main/java/com/velora/api/shipping/web/GeoController.java` · `src/main/java/com/velora/api/common/web/PingController.java` · `src/main/java/com/velora/api/cart/web/CartController.java` · `src/main/java/com/velora/api/cart/service/CartService.java:83-123,126-141,265-294,235-252` · `src/main/java/com/velora/api/shipping/web/ShippingController.java` · `src/main/java/com/velora/api/shipping/service/ShippingService.java:173-199` · `src/main/java/com/velora/api/order/web/CheckoutController.java:74-112` · `src/main/java/com/velora/api/order/service/CheckoutService.java:126-236` · `src/main/java/com/velora/api/customrequest/web/CustomRequestController.java` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:106-171,233-283` · `src/main/java/com/velora/api/common/security/SecurityConfig.java:115`

### 4.2 العميل المصادَق (`/me/*` وبعض `/auth/*`)

| الطلب | الغرض | المدخلات | ما يتغير | الاستجابة | الإخفاق |
|---|---|---|---|---|---|
| `POST /auth/me` | بيانات الرمز | — | لا شيء | `UserResponse` **جزئي**: `id, email, phone, roles` من الرمز؛ `firstName/lastName/fullName` غائبة؛ `emailVerified/phoneVerified` دائمًا `false`؛ `locale` دائمًا `"ar"` | — (هذا الـ endpoint `POST`) |
| `POST /auth/logout-all` | خروج من كل الأجهزة | — | يُلغي كل refresh tokens | `{message}` | — |
| `GET /me/orders` | طلباتي | `page`,`size`(10) | — | `PageResponse<OrderSummaryResponse>` الأحدث أولًا | — |
| `GET /me/orders/{orderNumber}` | طلب واحد بكل تفاصيله والسجل الزمني | — | — | `OrderResponse` | `ORDER_NOT_FOUND` 404 (غير موجود أو ليس لك) |
| `POST /me/orders/{orderNumber}/cancel` | إلغاء قبل الشحن | `reason?`(≤255) | `CANCELLED` + تحرير المخزون + سجل | `OrderResponse` | `ORDER_NOT_FOUND` 404؛ `ORDER_CANNOT_BE_CANCELLED` 409 إن `cancellable=false`؛ `INVALID_STATUS_TRANSITION` 409 احتياطًا فقط (انظر 3.1) — للمرتجعة وغيرها `ORDER_CANNOT_BE_CANCELLED` |
| `GET /me/addresses` | دفتر العناوين | — | — | قائمة غير مرقّمة، الافتراضي أولًا | — |
| `POST /me/addresses` | إضافة | `AddressRequest`: `recipientName*` `phone*` `governorateId*` `streetAddress*` `label` `altPhone` `area` `building` `floor` `apartment` `landmark` `makeDefault` | يحفظ؛ الأول يصير افتراضيًا تلقائيًا | 201 `AddressResponse` | `INVALID_PHONE_FORMAT`، `RESOURCE_NOT_FOUND` (محافظة)، `GOVERNORATE_NOT_SERVED` 409، `VALIDATION_FAILED` (أكثر من 10 عناوين) |
| `PUT /me/addresses/{addressId}` | تعديل | نفس الجسم | **استبدال كامل** (4.4) | `AddressResponse` | `RESOURCE_NOT_FOUND` 404 (ليس لك/محذوف) |
| `DELETE /me/addresses/{addressId}` | حذف ناعم | — | `deletedAt`؛ إن كان الافتراضي يُعيَّن غيره | 204 | 404 |
| `PUT /me/addresses/{addressId}/default` | جعله الافتراضي | — | — | `AddressResponse` | 404 |
| `GET /me/invoices/{invoiceNumber}/pdf` | تنزيل فاتورتي | — | — | PDF (`inline`) | `RESOURCE_NOT_FOUND` 404 (غير موجودة أو ليست لك) |
| `POST /cart/merge` | دمج سلة الضيف بعد الدخول | جسم `guestToken*` | يضم الكميات (مقصوصة على المتاح) ويضع سلة الضيف `MERGED` | `CartResponse` | `TOKEN_INVALID` 401 |

- `AddressResponse` يعيد `isDefault` (بينما الطلب يستعمل `makeDefault`) ورقم الهاتف بالصيغة المحلية `01…`.
- لا يوجد endpoint للعميل لـ: تعديل ملفه الشخصي، تغيير كلمة المرور وهو مسجَّل، حذف الحساب، قراءة/سرد طلباته الخاصة، تتبع مرتجع، مراجعات، قائمة أمنيات.

المصدر: `src/main/java/com/velora/api/identity/web/AuthController.java:101-107,93-96` · `src/main/java/com/velora/api/order/web/OrderController.java` · `src/main/java/com/velora/api/order/service/OrderService.java:75-81,296-310` · `src/main/java/com/velora/api/customer/web/AddressController.java` · `src/main/java/com/velora/api/customer/service/AddressService.java:60-83,86-96,104-123,126-131,33` · `src/main/java/com/velora/api/invoice/web/CustomerInvoiceController.java` · `src/main/java/com/velora/api/cart/web/CartController.java:146-152`

### 4.3 الأدمن (`/api/v1/admin/**` — دور `ADMIN`)

بلا رمز أو برمز منتهٍ = `UNAUTHORIZED` 401؛ مستخدم مسجَّل غير أدمن = `FORBIDDEN` 403. لا تمييز بين أدمن وآخر.

**الطلبات والدفع والتسويات**

| الطلب | الغرض | المدخلات | ما يتغير | الاستجابة | الإخفاق |
|---|---|---|---|---|---|
| `GET /admin/orders` | قائمة الطلبات | `phone?` (يُطبَّع؛ **له الأولوية ويتجاهل `status`**) `status?` (اسم FulfillmentStatus) `page`,`size`(20) | — | `PageResponse<OrderSummaryResponse>` | `INVALID_PARAMETER` 400 لحالة مجهولة. لا فلتر بحالة الدفع هنا (متاح في التصدير فقط). |
| `GET /admin/orders/{orderId}` | طلب كامل | — | — | `OrderResponse` (+`hasCancelledInvoices: true` فقط إن كان للطلب فاتورة ملغاة؛ يغيب غير ذلك، ولا يظهر في ردود العميل) | `ORDER_NOT_FOUND` 404 |
| `POST /admin/orders/{orderId}/confirm` | تأكيد هاتفي | query `note?` | `PENDING→CONFIRMED` | `OrderResponse` | `INVALID_STATUS_TRANSITION` 409 |
| `PATCH /admin/orders/{orderId}/fulfillment-status` | تغيير حالة التنفيذ | جسم `status*` `note?`(≤500؛ **إلزامي** لـ`DELIVERY_FAILED\|REFUSED_ON_DELIVERY\|CANCELLED\|RETURNED_TO_SELLER`) | انظر 3.1 | `OrderResponse` | `INVALID_PARAMETER` 400 (حالة مجهولة)، `INVALID_STATUS_TRANSITION` 409، `DELIVERY_NOT_SCHEDULED` 409، `VALIDATION_FAILED`، `STOCK_UNAVAILABLE` 409 (عند SHIPPED) |
| `PATCH /admin/orders/{orderId}/payment-status` | تغيير حالة الدفع يدويًا | **query**: `status*` `note?` | انظر 3.2 | `OrderResponse` | `INVALID_PARAMETER` 400، `INVALID_STATUS_TRANSITION` 409 |
| `PATCH /admin/orders/{orderId}/schedule` | موعد التسليم | جسم `scheduledDeliveryAt*`(مع الإزاحة) `note?`(≤500) | `scheduled_delivery_at` + تدقيق | `OrderResponse` | `ORDER_NOT_SCHEDULABLE` 409، `VALIDATION_FAILED` (ليس في المستقبل) |
| `POST /admin/orders/{orderId}/cancel` | إلغاء إداري | جسم `reason?`(≤255) | `CANCELLED` + `cancel_reason` + تحرير الحجز | `OrderResponse` | `INVALID_STATUS_TRANSITION` 409 (بعد الشحن) |
| `GET /admin/remittances/outstanding` | ما لم يورّده الكوريير | — | — | `{orderCount, totalAmount, orders[{orderId, orderNumber, customerName, governorate, amount, deliveredAt, daysWaiting}]}` الأقدم أولًا | — |
| `POST /admin/remittances` | تسجيل تسوية | `courierName*`(≤150) `courierReference`(≤100) `settlementDate`(الافتراضي اليوم) `orderIds*`(غير فارغة) `receivedAmount*`(≥0) `note`(≤1000؛ إلزامي عند أي فرق) | ينشئ التسوية؛ كل طلب `PAID` | 201 `RemittanceResponse` | `VALIDATION_FAILED` (طلب غير COD/غير DELIVERED/غير PENDING، أو فرق بلا ملاحظة)، `ORDER_NOT_FOUND` 404، `DUPLICATE_VALUE` 409 (طلب داخل تسوية **فعّالة** أخرى؛ الطلب الذي أُلغيت تسويته يُقبل من جديد) |
| `GET /admin/remittances` ، `GET /admin/remittances/{id}` | سجل التسويات | `page`,`size`(20) | — | `PageResponse<RemittanceResponse>` / `RemittanceResponse` | `RESOURCE_NOT_FOUND` 404 |
| `POST /admin/remittances/{id}/cancel` | إلغاء تسوية | **query** `reason*` | `CANCELLED`؛ الطلبات `PENDING` | `RemittanceResponse` | `INVALID_STATUS_TRANSITION` 409 (ملغاة سلفًا)، `VALIDATION_FAILED` (سبب فارغ)، `INVALID_PARAMETER` 400 (بلا `reason`) |

**الفواتير والتصدير والتقارير**

| الطلب | الغرض | المدخلات | الاستجابة | ملاحظات |
|---|---|---|---|---|
| `GET /admin/invoices` | قائمة | `orderId?` (اختياري: فواتير ذلك الطلب كلها **بما فيها الملغاة**، الأحدث أولًا)، `page`,`size`(20) | `PageResponse<InvoiceResponse>` | بدون `orderId` السلوك القديم. كل عنصر فيه `status` و`replacesInvoiceNumber` (إن وُجد). `pdfUrl` = مسار `GET /admin/invoices/{id}/pdf` (يحتاج التوكن؛ يُجلب كـ blob) وليس رابطًا عامًا. `orderId` لا فواتير له = صفحة فارغة. |
| `GET /admin/invoices/{invoiceId}` | واحدة | — | `InvoiceResponse` | 404 |
| `GET /admin/invoices/{invoiceId}/pdf` | تنزيل | — | PDF | يُعاد توليده من لقطة الطلب |
| `POST /admin/invoices/issue/{orderId}` | إصدار يدوي أو **بديل لفاتورة ملغاة** | — | `InvoiceResponse` | idempotent لفاتورة حيّة؛ إن كانت فواتير الطلب كلها ملغاة تُصدر جديدة برقم التالي مع `replacesInvoiceNumber`؛ `INVALID_STATUS_TRANSITION` 409 إن لم يكن الطلب `DELIVERED` |
| `POST /admin/invoices/{invoiceId}/cancel` | إلغاء | جسم `reason*`(≤255) | `InvoiceResponse` | يُسجَّل `INVOICE_CANCELLED` في التدقيق؛ `INVALID_STATUS_TRANSITION` 409 إن ملغاة |
| `GET /admin/invoices/reconciliation/uninvoiced` | فحص مصالحة | — | `{count, orderIds}` | يجب أن تكون فارغة |
| `GET /admin/exports/orders/accounting` | Excel محاسبي | `dateFrom` `dateTo` `fulfillmentStatus` `paymentStatus` `governorateId` `excludeCancelled`(الافتراضي true) | `.xlsx` | **سقف 5000 صف يُقتطع بصمت** (تحذير في اللوغ فقط)؛ `RESOURCE_NOT_FOUND` 404 إن لا نتائج |
| `GET /admin/exports/orders/picking-list` | قائمة تجهيز PDF | نفس المرشحات؛ بلا `fulfillmentStatus` = `CONFIRMED` | PDF | سقف 300 طلب بصمت؛ مبلغ التحصيل يُطبع لطلبات COD+PENDING فقط |
| `GET /admin/dashboard` | لوحة المالك | — | `DashboardResponse` | انظر 5 |
| `GET /admin/audit` | سجل التدقيق | `action?` `actorId?` `page`,`size`(50) | `PageResponse<AuditLogResponse>` | تغييرات حالة الطلب ليست هنا |
| `GET /admin/audit/{entityType}/{entityId}` | تاريخ كيان | — | `PageResponse<AuditLogResponse>` | `entityType` يُحوَّل لأحرف كبيرة |
| `GET /admin/settings/store-profile` | بيانات البائع | — | `StoreProfileResponse` (+`missingFields[]`) | — |
| `PUT /admin/settings/store-profile` | تعديلها | `legalName*`(≤200) `legalNameEn` `address`(≤500) `phone`(≤30) `email` `taxNumber`(9 أرقام `###-###-###` أو فارغ) `commercialRegister` `website` `invoiceFooterNote`(≤500) | `StoreProfileResponse` | **استبدال كامل** (4.4) |

**المخزون**

| الطلب | الغرض | المدخلات | الاستجابة / الإخفاق |
|---|---|---|---|
| `GET /admin/inventory` | قائمة مرقّمة | `q` `lowStockOnly` `outOfStockOnly` `categoryId` `sort`(`available_asc` الافتراضي أو `available_desc`) `page`,`size`(20) | `PageResponse<InventoryAdminResponse>` |
| `GET /admin/inventory/{variantId}` | موقف متغيّر | — | `InventoryAdminResponse`؛ `VARIANT_NOT_FOUND`/`INVENTORY_RECORD_MISSING` |
| `GET /admin/inventory/low-stock` | النقص | — | قائمة غير مرقّمة (`available ≤ minStockLevel`) |
| `POST /admin/inventory/{variantId}/receive` | استلام بضاعة | `quantity*`(>0) `reference`(≤60) `note`(≤500) | `PURCHASE_RECEIVED`؛ يكتب `STOCK_RECEIVED` تدقيقًا |
| `POST /admin/inventory/{variantId}/adjust` | تسوية يدوية | `quantityDelta*` `reason*`(≤500) `movementType`(`MANUAL_ADJUSTMENT` الافتراضي، أو `DAMAGE_WRITEOFF`، أو `PURCHASE_RECEIVED`) | `VALIDATION_FAILED` (دلتا=0 / سبب فارغ / نوع مجهول)، `NEGATIVE_STOCK` 400، `STOCK_BELOW_RESERVED` 409، `MOVEMENT_TYPE_NOT_MANUAL` 400، `CONCURRENT_STOCK_CHANGE` 409 |
| `GET /admin/inventory/movements` | دفتر الحركات | `variantId?` `page`,`size`(50) | `PageResponse<StockMovementResponse>` |

**الكتالوج**

| الطلب | الغرض | الاستجابة / ملاحظات |
|---|---|---|
| `GET /admin/products` | كل المنتجات (مسودات ومؤرشفة) | `PageResponse<ProductAdminResponse>` (20)؛ **بلا فلاتر أو بحث** |
| `GET /admin/products/{id}` | منتج | `ProductAdminResponse` (+`warnings[]` نصوص إنجليزية: بلا متغيّرات/صور/ترجمة إنجليزية/نفد) |
| `POST /admin/products` | إنشاء (يبدأ `DRAFT`) | 201؛ `categoryId*` `translations*`(≥1) `slug` `brandId` `featured` `newArrival` `specifications` `fulfillmentType`(READY_MADE) `shippingSizeClass`(إلزامي لـREADY_MADE) `requiresAssembly` `assemblyFee`(≥0) |
| `PUT /admin/products/{id}` | تعديل | **استبدال جزئي/كامل مختلط** (4.4) |
| `PATCH /admin/products/{id}/publish` / `unpublish` / `archive` | حالة المنتج | 3.9 |
| `POST /admin/products/{id}/duplicate` | نسخ | 201؛ يُنسخ المحتوى (يُلحق `(copy)` بالاسم) ولا تُنسخ المتغيّرات/الصور/المواصفات؛ يصير `DRAFT` |
| `GET /admin/products/{id}/images` ، `POST` (multipart: `file*` + query `variantId?`)، `PUT /{imageId}`، `DELETE /{imageId}` | الصور | حتى 20 صورة؛ 5MB؛ JPEG/PNG/WebP/AVIF بفحص البايتات؛ الحذف 204 ويضمن صورة رئيسية |
| `GET /admin/products/{productId}/variants` | متغيّرات منتج | قائمة غير مرقّمة |
| `POST /admin/products/{productId}/variants/preview` | معاينة مصفوفة المتغيّرات | `selections[{attributeId*, valueIds*}]` → `VariantMatrixPreviewResponse` بلا حفظ |
| `POST /admin/products/{productId}/variants` | إنشاء/تعديل متغيّرات | `variants[{id?, sku, barcode, price*, compareAtPrice, costPrice, taxRate, weightGrams, attributeValueIds, initialStock, minStockLevel}]` → قائمة `VariantAdminResponse`؛ **`id` موجود = تعديل بـ POST** (4.4)؛ الكومبينيشن الموجود يُتخطى بصمت |
| `DELETE /admin/variants/{variantId}` | أرشفة متغيّر | 204 |
| `GET /admin/categories` ، `POST` ، `PUT /{id}` ، `POST /{id}/images?imageType=CARD` (أو `BANNER`) (multipart `file`) ، `DELETE /{id}/images/{imageType}` | التصنيفات | الإنشاء 201 `{id}`؛ التعديل `{id}`؛ **استبدال كامل** (4.4) **ما عدا `scope`** (غيابه = بلا تغيير). `GET ?scope=PRODUCT\|PORTFOLIO` (اختياري؛ غيره `INVALID_PARAMETER` 400) وكل عقدة تحمل `scope`؛ `scope` في الجسم اختياري (إنشاء: `PRODUCT` أو نطاق الأب). أخطاء: `CATEGORY_SCOPE_MISMATCH` 400، `CATEGORY_SCOPE_IN_USE` 409 |
| `GET /admin/brands` ، `POST` ، `PUT /{id}` | العلامات | `nameAr*` `nameEn*` `logoUrl`(نص رابط لا رفع) `slug` `active` |
| `GET /admin/attributes?variantDefining=` ، `POST` ، `PUT /{id}` | الخصائص وقيمها | **استبدال** (4.4)؛ `code` UPPER_SNAKE |

**الشحن**

| الطلب | الغرض | المدخلات | ملاحظات |
|---|---|---|---|
| `GET /admin/shipping/zones` | المناطق وأسعارها | — | `rates[{sizeClass, unitCost}]`, `maxShippingCost`, `codFee`, `deliveryDaysMin/Max`, `governorates[]` (أسماء) |
| `PUT /admin/shipping/rates` | سعر حجم في منطقة | `zoneId*` `sizeClass*` `baseCost*`(≥0) `codFee?` `deliveryDaysMin?` `deliveryDaysMax?`(≥0) | `{id}`؛ تدقيق `SHIPPING_RATE_CHANGED`؛ min>max = `VALIDATION_FAILED` |
| `GET /admin/shipping/governorates` | كل المحافظات | — | `served` + المنطقة |
| `PUT /admin/shipping/governorates/{governorateId}/zone` | فتح محافظة/نقلها | `zoneId*` | `SHIPPING_RATE_NOT_CONFIGURED` 409 إن لم تكن المنطقة مسعَّرة لـ3 أحجام |
| `DELETE /admin/shipping/governorates/{governorateId}/zone` | إغلاقها | — | idempotent |
| `PUT /admin/shipping/zones/{zoneId}/max-shipping-cost` | سقف الشحن | `maxShippingCost?`(≥0؛ `null` يزيل السقف) | تدقيق عند تغيّر القيمة فقط |

**الطلبات الخاصة والمعرض والعملاء**

| الطلب | الغرض | المدخلات | ملاحظات |
|---|---|---|---|
| `GET /admin/custom-requests` | قائمة | `status` `type` `governorateId` `q` `from` `to` `page`,`size`(20؛ الأحدث أولًا) | `PageResponse<CustomRequestSummaryResponse>` |
| `GET /admin/custom-requests/{id}` | تفاصيل | — | `CustomRequestResponse` (+`governorateServed`) ؛ `CUSTOM_REQUEST_NOT_FOUND` 404 |
| `PATCH /admin/custom-requests/{id}/status` | تغيير حالة | `status*` `note?`(≤1000؛ إلزامي لـREJECTED) | 3.3 |
| `PATCH /admin/custom-requests/{id}/quote` | سعر | `amount*`(0.01–100,000,000) `note?` | 3.3 |
| `GET /admin/portfolio` | القائمة | `includeArchived`(false) `categoryId` `page`,`size`(20) | — |
| `GET /admin/portfolio/{id}` ، `POST` (201، يبدأ غير منشور) ، `PUT /{id}` (**استبدال**) ، `PATCH /{id}/publish` `{published*}` ، `DELETE /{id}` (أرشفة، 200 بجسم) ، `PATCH /{id}/restore` | دورة الحياة | — (انظر الأجسام في 4.4) | 3.9 |
| `GET /admin/portfolio/{id}/images` ، `POST` (multipart `file`، 201) ، `PATCH /{imageId}` ، `DELETE /{imageId}` | الصور | رفع: `file`(multipart)؛ تعديل: `altTextAr` `altTextEn` `main` `displayOrder` | حتى 20؛ الأولى تصير الرئيسية |
| `GET /admin/customers` | العملاء | `search` (اسم/هاتف مُطبَّع/بريد) `sort`(`spent_desc` أو `orders_desc` أو `recent_order` أو `name`) `page`,`size`(25) | العدّ والإجمالي يستثنيان الملغاة |
| `GET /admin/customers/{customerId}` | تفاصيل عميل | — | يتضمن `purchases.failedOrders` وآخر طلباته وعناوينه |

- **ما لا يوجد للأدمن:** إدارة المستخدمين/الأدوار، حذف طلب، تعديل بنود طلب، استرداد، إدارة مرتجعات، تحويل طلب خاص إلى طلب، إرسال بريد/رسالة للعميل.

المصدر: `src/main/java/com/velora/api/order/web/AdminOrderController.java` · `src/main/java/com/velora/api/order/service/OrderService.java:107-122` · `src/main/java/com/velora/api/remittance/web/AdminRemittanceController.java` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:104-195,204-240` · `src/main/java/com/velora/api/invoice/web/AdminInvoiceController.java` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:276-282` · `src/main/java/com/velora/api/export/web/AdminExportController.java` · `src/main/java/com/velora/api/export/service/OrderExportService.java:73-83,93-108,112-134,48,49` · `src/main/java/com/velora/api/dashboard/web/AdminDashboardController.java` · `src/main/java/com/velora/api/audit/web/AdminAuditController.java` · `src/main/java/com/velora/api/store/web/AdminStoreProfileController.java` · `src/main/java/com/velora/api/store/dto/StoreProfileRequest.java` · `src/main/java/com/velora/api/inventory/web/AdminInventoryController.java` · `src/main/java/com/velora/api/inventory/service/InventoryAdminService.java:149-210` · `src/main/java/com/velora/api/catalog/web/admin/AdminProductController.java` · `src/main/java/com/velora/api/catalog/service/admin/ProductImageService.java:30` · `src/main/java/com/velora/api/catalog/web/admin/AdminVariantController.java` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:128-155` · `src/main/java/com/velora/api/catalog/web/admin/AdminTaxonomyController.java` · `src/main/java/com/velora/api/shipping/web/AdminShippingController.java` · `src/main/java/com/velora/api/shipping/service/ShippingAdminService.java:108-178,182-201,251-287` · `src/main/java/com/velora/api/customrequest/web/AdminCustomRequestController.java` · `src/main/java/com/velora/api/portfolio/web/admin/AdminPortfolioController.java` · `src/main/java/com/velora/api/portfolio/service/PortfolioImageService.java:31` · `src/main/java/com/velora/api/customer/web/AdminCustomerController.java`

### 4.4 نقاط الاستبدال الكامل (PUT / POST) التي تمسح الحقول الاختيارية غير المُرسلة

> هذه القائمة كاملة بحسب فحص كل `PUT` (والنقطة `POST` الوحيدة التي تُحدّث). "تمسح" = القيمة المخزَّنة تصير `null`/`false`/الافتراضي إن لم تُرسَل.

| النقطة | ما يُمسح عند الإغفال | ما يبقى كما هو عند الإغفال |
|---|---|---|
| **`PUT /admin/products/{id}`** | `brandId` (المنتج يفقد علامته)؛ `featured` و`newArrival` (تصيران `false`)؛ داخل كل ترجمة مُرسلة: `shortDescription`, `description`, `metaTitle`, `metaDescription` (تصير `null`) ويُعاد حساب `search_text`؛ **أي لغة غير مذكورة في `translations` تُحذف** (إن أُرسلت `translations` غير فارغة)؛ **`specifications` المُرسلة (حتى القائمة الفارغة `[]`) تحذف كل مواصفة غير مذكورة** | `translations` غائبة أو `[]` ⇒ بلا تغيير؛ `specifications` غائبة (`null`) ⇒ بلا تغيير؛ `slug`, `fulfillmentType`, `shippingSizeClass`, `requiresAssembly`, `assemblyFee` ⇒ بلا تغيير. `categoryId` إلزامي. |
| **`PUT /admin/categories/{id}`** | `parentId` ⇒ **ينتقل إلى الجذر**؛ في كل ترجمة: `description`, `metaTitle`, `metaDescription`؛ لغات غير مذكورة تُحذف | `slug`, `displayOrder`, `active` ⇒ بلا تغيير. **`scope` ⇒ بلا تغيير (الاستثناء الوحيد، انظر الصندوق أدناه)** |
| **`PUT /admin/brands/{id}`** | `logoUrl` | `slug`, `active`. `nameAr` و`nameEn` إلزاميان |
| **`PUT /admin/attributes/{id}`** | `dataType` ⇒ **يعود `LIST`**؛ `filterable` ⇒ `false`؛ `variantDefining` ⇒ `false` (يُرفض `ATTRIBUTE_IN_USE` إن اختلف عن المخزَّن)؛ ترجمات الاسم غير المذكورة تُحذف؛ لكل قيمة مُرسلة: `hexColor` (تُمسح) وترجمات القيمة غير المذكورة (تُحذف) | `displayOrder` للخاصية و`displayOrder` لكل قيمة ⇒ بلا تغيير؛ `values` غائبة ⇒ لا تغيير; **قيمة غير مذكورة في `values` لا تُحذف أبدًا** (تُحدَّث المذكورة بمطابقة `id` فقط، وقيمة بلا `id` تُنشأ جديدة، فإن تكرر `code` موجود فشلت بـ`ATTRIBUTE_VALUE_CODE_EXISTS`/`DUPLICATE_VALUE`) |
| **`PUT /admin/settings/store-profile`** | **كل** الحقول عدا `legalName`: `legalNameEn, address, phone, email, taxNumber, commercialRegister, website, invoiceFooterNote` (القيمة الفارغة تُخزَّن `null`) | — |
| **`PUT /admin/portfolio/{id}`** | `titleEn`, `descriptionAr`, `descriptionEn`, `categoryId`, `completedAt` | `slug`, `displayOrder`. `titleAr` إلزامي |
| **`PUT /me/addresses/{addressId}`** | `label`, `altPhone`, `area`, `building`, `floor`, `apartment`, `landmark` | `makeDefault` غائب ⇒ لا يُلغي الافتراضي. `recipientName`, `phone`, `governorateId`, `streetAddress` إلزامية |
| **`POST /admin/products/{productId}/variants` مع `id`** (تعديل) | `barcode`, `compareAtPrice`, `costPrice` (`null`)؛ `price` إلزامي | `sku`, `taxRate`, `weightGrams`؛ و`attributeValueIds`, `initialStock`, `minStockLevel` **تُتجاهل كليًا عند التعديل** (لا يمكن تعديل المخزون ولا الخصائص من هنا) |

> ### ⚠ استثناء وحيد من الاستبدال الكامل: `scope` التصنيف
> كل حقول `PUT /admin/categories/{id}` تُستبدل استبدالًا كاملًا (الغائب `parentId` ينقل التصنيف إلى الجذر، والترجمات غير المذكورة تُحذف)، **إلا `scope`: غيابه أو `null` يعني «بلا تغيير».**
>
> **السبب:** لو عنى الغياب «أعد إلى `PRODUCT`» لتحوّل تصنيف معرض بصمت إلى تصنيف منتجات عند أي عميل لا يُرسل الحقل بعد (فورم قديم، سكربت)، فيظهر في قائمة المتجر ولا يلاحظ أحد حتى يفتح عميل تصنيفًا فارغًا. أما «بلا تغيير» فمن ينسى الحقل لا يغيّر شيئًا — يفشل آمنًا بدل أن يفشل صامتًا وضارًّا.
>
> فلا تفترض أن `scope` يتصرف كبقية الحقول، ولا «تصلحه» ليطابقها. لتغيير النطاق أرسل القيمة الجديدة صراحةً؛ ويُرفض بـ`CATEGORY_SCOPE_IN_USE` (409) ما دام للتصنيف أولاد أو منتجات أو عناصر معرض (المؤرشفة تُحسب). المنفِّذ: `TaxonomyAdminService.applyScope` وفوقه تعليق بنفس الشرح.

نقاط `PUT` **ليست** استبدالًا ماسحًا: `PUT /admin/shipping/rates` (الحقول الاختيارية الثلاثة `null` = بلا تغيير)، `PUT /admin/shipping/governorates/{id}/zone` و`PUT /me/addresses/{id}/default` (بلا حقول اختيارية)، `PUT /admin/shipping/zones/{zoneId}/max-shipping-cost` (الحقل وحده؛ `null` = إزالة السقف وهو المقصود)، `PUT /admin/products/{id}/images/{imageId}` (دمج: `null` = بلا تغيير — أي **لا يمكن** مسح نص بديل أو فك ربط الصورة بمتغيّر رغم أن الوصف يقول إن `variantId = null` يعني "مشتركة").

**أُصلح: إعادة إرسال `slug` التصنيف نفسه.** كان `PUT /admin/categories/{id}` يعيد توليد السلاج بـ`generateUnique` وفحصه يرى التصنيف نفسه «مأخوذًا» فيصير `<slug>-2` ثم `-3` مع كل حفظ (والاستجابة تعيد `slug` والواجهة تعيده كاملًا). صار الفحص يستثني معرّف التصنيف (`existsBySlugAndIdNot`)؛ تعارض حقيقي مع تصنيف آخر ما زال يُعطي `-2`. (المنتج والعلامة والمعرض كانوا سليمين.) اختبار: `CategorySlugIntegrationTest`.

المصدر: `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:107-147,243-282,313-387,434-441` · `src/main/java/com/velora/api/catalog/dto/admin/ProductUpdateRequest.java` · `src/main/java/com/velora/api/catalog/service/admin/TaxonomyAdminService.java:220-225,227-261,325-356,361-406,408-438,455-476,478-495,497-514` · `src/main/java/com/velora/api/common/util/SlugGenerator.java:58-73` · `src/main/java/com/velora/api/catalog/dto/admin/CategorySaveRequest.java` · `src/main/java/com/velora/api/catalog/dto/admin/AttributeSaveRequest.java` · `src/main/java/com/velora/api/store/service/StoreProfileService.java:58-77` · `src/main/java/com/velora/api/portfolio/service/PortfolioAdminService.java:89-116` · `src/main/java/com/velora/api/portfolio/dto/admin/PortfolioUpdateRequest.java` · `src/main/java/com/velora/api/customer/service/AddressService.java:86-96,141-175` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:198-241` · `src/main/java/com/velora/api/shipping/service/ShippingAdminService.java:108-178` · `src/main/java/com/velora/api/catalog/service/admin/ProductImageService.java:75-100`

### 4.5 حقول يختلف اسمها بين الطلب والاستجابة

| السياق | في الطلب | في الاستجابة |
|---|---|---|
| إنشاء طلب — بيانات المستلم | `address.recipientName`, `address.phone`, `address.altPhone`, `address.email` | `contactName`, `contactPhone`, `contactAltPhone`, `contactEmail` (والهاتف يعود بالصيغة المحلية `01…`) |
| إنشاء طلب — العنوان | `address{governorateId, area, streetAddress, building, floor, apartment, landmark}` | `shippingAddress{governorateName, cityName, area, streetAddress, building, floor, apartment, landmark, formatted}` (اسم المحافظة نصًا لا معرّفًا) |
| مبالغ الطلب | — | `subtotal` (من العمود `subtotal_gross`)، `discountTotal`, `shippingCost`, `shippingUncappedCost`, `codFee`, `assemblyTotal`, `grandTotal`, `taxTotal`, `netTotal` |
| سطر الطلب | — | `unitPrice` (=`unit_price_gross`)، `lineTotal` (=`line_total_gross`)، `taxAmount` (=`line_tax_amount`)، `productSlug` (حي لا لقطة) |
| الحالة | تغيير الحالة: `status` ؛ الدفع: query `status` | `fulfillmentStatus`, `paymentStatus` ؛ السجل: `timeline[{kind, from, to, note, at}]` |
| الإلغاء | `reason` | `cancelReason` |
| الإلغاء (فاتورة) | `reason` | `cancelReason` |
| العنوان المحفوظ | `makeDefault` | `isDefault` ؛ والهاتف يعود محليًا |
| الطلب الخاص — السعر | `amount` (في `/quote`) | `quotedAmount` |
| الطلب الخاص — الملاحظة | `note` (في `/status` و`/quote`) | `adminNote` |
| الطلب الخاص — المنتج | `productId` | `product{id, slug, name, fulfillmentType}` |
| الطلب الخاص — المحافظة | `governorateId` | `governorateId`, `governorateName`, `governorateServed` |
| رمز مرفقات الطلب الخاص | يُرجَع في الجسم `attachmentToken` | يُرسَل **كرأس** `X-Request-Token` |
| التسوية | `orderIds`, `receivedAmount` | `orders[{orderId, orderNumber, amount}]`, `receivedAmount`, `expectedAmount`, `difference` |
| سعر الشحن | `baseCost` (في `PUT /admin/shipping/rates`) | `unitCost` (في `rates[]` و`shippingRates[]` والتفاصيل `breakdown[].unitCost`) |
| الخصائص (Attributes) | `translations[{locale, name}]`, `values[].translations[]` | `nameAr`, `nameEn`, `values[].nameAr/nameEn` (مسطّحة) — **لا تتطابق مع شكل الطلب** |
| المنتج | `translations[]`, `brandId`, `categoryId` | `translations[]`, `nameAr`, `nameEn`, `categoryId`, `categoryName`, `brandId`, `brandName` |
| المخزون — استلام | `quantity`, `reference`, `note` | حركة: `quantityDelta`, `referenceId`, `reason` |
| الكمية المتاحة | — | **تسميتان مختلفتان:** `availableQty` (الكتالوج العام، `availability`) و`qtyAvailable` (السلة، المخزون الإداري، المتغيّر الإداري) |
| إنشاء متغيّر | `initialStock`, `minStockLevel`, `costPrice` | `qtyOnHand`, `qtyReserved`, `qtyAvailable`, `minStockLevel` ؛ `costPrice` **لا يعود أبدًا** |
| رمز الضيف | رأس `X-Guest-Token` | جسم `guestToken` (من `/cart/guest-token`) وجسم الدمج `guestToken` |
| المصادقة | `identifier` (هاتف/بريد) | `user.phone`, `user.email` (منفصلان) ؛ `expiresIn` بالثواني |
| ملف المتجر | نفس الأسماء | + `missingFields[]` للقراءة فقط |

المصدر: `src/main/java/com/velora/api/order/dto/PlaceOrderRequest.java` · `src/main/java/com/velora/api/order/dto/OrderResponse.java` · `src/main/java/com/velora/api/order/dto/OrderItemResponse.java` · `src/main/java/com/velora/api/order/service/OrderService.java:392-394,467-488` · `src/main/java/com/velora/api/customer/dto/AddressRequest.java` · `src/main/java/com/velora/api/customer/dto/AddressResponse.java` · `src/main/java/com/velora/api/customrequest/dto/CustomRequestResponse.java` · `src/main/java/com/velora/api/customrequest/dto/CustomRequestQuoteRequest.java` · `src/main/java/com/velora/api/customrequest/web/CustomRequestController.java:81-94` · `src/main/java/com/velora/api/remittance/dto/CreateRemittanceRequest.java` · `src/main/java/com/velora/api/remittance/dto/RemittanceResponse.java` · `src/main/java/com/velora/api/shipping/dto/ShippingRateRequest.java` · `src/main/java/com/velora/api/shipping/dto/SizeRateResponse.java` · `src/main/java/com/velora/api/catalog/dto/admin/AttributeSaveRequest.java` · `src/main/java/com/velora/api/catalog/dto/admin/AttributeAdminResponse.java` · `src/main/java/com/velora/api/catalog/dto/admin/ProductAdminResponse.java` · `src/main/java/com/velora/api/inventory/dto/StockReceiveRequest.java` · `src/main/java/com/velora/api/inventory/dto/StockMovementResponse.java` · `src/main/java/com/velora/api/catalog/dto/admin/VariantAdminResponse.java` · `src/main/java/com/velora/api/catalog/dto/admin/VariantSaveRequest.java` · `src/main/java/com/velora/api/cart/dto/CartItemResponse.java` · `src/main/java/com/velora/api/inventory/dto/InventoryAdminResponse.java` · `src/main/java/com/velora/api/catalog/web/VariantController.java:34-45` · `src/main/java/com/velora/api/identity/dto/AuthResponse.java`

### 4.6 حدود المعدل وصلاحية الرموز وحدود الحجم والعدد

**حدود المعدل (`RateLimiter`: نافذة ثابتة في ذاكرة العملية، تُفقد عند إعادة التشغيل ولا تُشارَك بين نسخ)**

| ما | الحد | النافذة | المفتاح | الكود عند التجاوز |
|---|---|---|---|---|
| `POST /auth/login` | 10 | 15 دقيقة | IP | `RATE_LIMITED` 429 |
| `POST /auth/login` | 5 | 15 دقيقة | المعرّف (هاتف/بريد) | `RATE_LIMITED` 429 (**كل محاولة تُحسب، ناجحة أو فاشلة**) |
| `POST /auth/otp/send` | 10 | 60 دقيقة | IP | `RATE_LIMITED` 429 |
| `POST /auth/otp/send` | 5 | ساعة | الوجهة (من قاعدة البيانات، مثبتة في الكود) | `OTP_TOO_MANY_ATTEMPTS` 429 |
| `POST /auth/otp/verify` | 5 محاولات خاطئة لكل رمز | عمر الرمز | الرمز | `OTP_TOO_MANY_ATTEMPTS` 429 — لا حد IP |
| `POST /custom-requests` | 10 | 60 دقيقة | IP | `RATE_LIMITED` 429 |
| `POST /custom-requests/{id}/attachments` | 30 | 60 دقيقة | IP | `RATE_LIMITED` 429 |
| غير ذلك (بما فيه `POST /orders`, `/auth/register`, `/shipping/quote`, `/cart/*`) | **لا يوجد** | — | — | — |

عنوان IP يُحسب من `X-Forwarded-For` من اليمين وفقط إن كان الاتصال المباشر من عنوان خاص/loopback؛ وإلا يُستعمل عنوان الاتصال.

**صلاحيات ومُدد**

| الرمز | المدة | ملاحظات |
|---|---|---|
| Access token (JWT HS512) | 30 دقيقة (`access-token-minutes`) | يحمل الدور؛ لا فحص قاعدة بيانات لكل طلب |
| Refresh token | 30 يومًا (`refresh-token-days`) | rotation؛ مُجزَّأ في القاعدة |
| OTP | 10 دقائق | 6 أرقام |
| رمز إعادة كلمة المرور | ساعة | استعمال واحد |
| رمز مرفقات الطلب الخاص | 24 ساعة (`attachment-token-hours`) | مربوط بطلب واحد |
| رمز الضيف | بلا انتهاء | `<uuid>.<HMAC>` |
| مفتاح Idempotency | 24 ساعة | ثابت في الكود |

**حدود الحجم والعدد**

| ما | الحد |
|---|---|
| حجم ملف مرفوع | 5 MB (`velora.storage.max-file-size-bytes` و`spring.servlet.multipart.max-file-size`)، والطلب كله 10 MB؛ تجاوز Spring ⇒ `FILE_TOO_LARGE` 413 |
| نوع الملف | JPEG/PNG/WebP/AVIF، يُتحقق من البايتات الفعلية؛ الامتداد من النوع المكتشف |
| صور المنتج | 20 |
| صور عنصر المعرض | 20 |
| صور الطلب الخاص | 5 |
| أسطر السلة | 50 مختلفة؛ كمية السطر 1–99 |
| عناوين العميل | 10 |
| أبعاد الطلب الخاص | >0 و≤10000 سم؛ كمية 1–10000؛ ملاحظات ≤1000 |
| تاريخ التسليم المفضل | ليس في الماضي ولا بعد 90 يومًا (توقيت القاهرة) |
| كلمة المرور | 8–72 حرفًا |
| صفحة البحث العام للمنتجات | ≤60 للصفحة (اقتطاع صامت)؛ بقية الـ endpoints بلا سقف في الكود (حد Spring الافتراضي — انظر 8) |
| صفحة المعرض العامة | ≤50 للصفحة (اقتطاع صامت) ؛ الافتراضي 12 |
| معاينة مصفوفة المتغيّرات | أول 200 تركيبة فقط مع تحذير؛ التوليد يتوقف عند تجاوز ~2000 تركيبة |
| تصدير Excel | 5000 صف؛ قائمة التجهيز 300 طلب |
| `related` | 8 |

المصدر: `src/main/java/com/velora/api/identity/service/AuthService.java:131-163` · `src/main/java/com/velora/api/identity/service/OtpService.java:38,37` · `src/main/java/com/velora/api/identity/domain/OtpVerification.java:34` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:57,106-171` · `src/main/java/com/velora/api/common/ratelimit/RateLimiter.java:34-41` · `src/main/java/com/velora/api/common/ratelimit/ClientIpResolver.java:38-59` · `src/main/resources/application.yml:7,8,87,88,128,105` · `src/main/java/com/velora/api/common/storage/LocalStorageService.java:135-170` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:229-232` · `src/main/java/com/velora/api/order/service/CheckoutService.java:74` · `src/main/java/com/velora/api/idempotency/service/IdempotencyService.java:43` · `src/main/java/com/velora/api/portfolio/service/PortfolioQueryService.java:29` · `src/main/java/com/velora/api/catalog/service/admin/VariantMatrixGenerator.java:28,60-65`

## 5. ما يقرره الخادم ولا يجوز للعميل اشتقاقه

كل حقل هنا يعود في الاستجابة لأن حسابه محليًا سيخالف القاعدة الفعلية. القاعدة العامة: العميل يعرض ولا يحسب مالًا ولا حالةً ولا إتاحة.

### 5.1 الطلب والتنفيذ

| الحقل (الاستجابة) | ما يقرره الخادم | ما سيخطئ فيه العميل لو اشتقه |
|---|---|---|
| `OrderResponse.cancellable` | يُشتق من جدول الانتقالات نفسه الذي تفرضه عملية الإلغاء: `true` فقط في `PENDING, CONFIRMED, AWAITING_SCHEDULE, PROCESSING` (3.1) | افتراض «قبل الشحن فقط» أو قائمة حالات محلية: ستنحرف عن الخادم عند تغيّر الجدول. العلم والعملية متطابقان لكل قيمة في الـenum (اختبار `OrderCancellabilityTest`)، فالزر المفعَّل لا يُرفض بـ409 بعد الآن. |
| `OrderResponse.fulfillmentStatus` و`paymentStatus` | آلتان مستقلتان | دمجهما في شارة واحدة: `DELIVERED` + `PENDING` طبيعي للدفع عند الاستلام. |
| `OrderResponse.timeline[]` | سجل الحالتين بالترتيب الزمني (`kind` = `FULFILLMENT\|PAYMENT`) | استنتاج التسلسل من الحقول الزمنية (`confirmedAt`...) يفقد الملاحظات والمنفِّذ ولا يرى تغييرات الدفع. تسوية COD لا تظهر فيه (2.11). |
| `OrderResponse.invoiceNumber` | رقم الفاتورة **الحيّة** فقط؛ يغيب قبل `DELIVERED` ويغيب إن كانت فواتير الطلب كلها ملغاة | افتراض وجود فاتورة بمجرد التأكيد، أو توليد رقم محليًا، أو اعتبار غيابه بعد التسليم «لم تُصدَر قط» (قد تكون ملغاة — انظر الصف التالي). |
| `OrderResponse.hasCancelledInvoices` | في ردود **الأدمن** فقط: `true` إن كان للطلب فاتورة ملغاة، ويغيب (لا `false`) إن لم يكن | عرض «بلا فاتورة» لطلب سُلّم وأُلغيت فاتورته. عند `true` تستدعي الواجهة `GET /admin/invoices?orderId=` لتعرض كل فواتيره بحالاتها و`replacesInvoiceNumber`. لا يظهر في ردود العميل. |
| `OrderResponse.shippingCapApplied`, `shippingUncappedCost`, `shippingBreakdown` | لقطة كيف بُني الشحن | إعادة حساب الشحن بأسعار اليوم لعرضه على طلب قديم. للطلبات القديمة قد تغيب (`null` ⇒ غير مُطبَّق/غير مسجَّل). |
| `OrderResponse.subtotal, taxTotal, netTotal, grandTotal` و`items[].taxAmount` | مجاميع مخزَّنة، الضريبة لكل سطر ثم تجمع | جمع الأسطر أو اشتقاق الضريبة من الإجمالي بفارق قروش؛ و`netTotal` يتضمن الشحن وCOD (2.3). |
| `OrderResponse.items[].returnableQuantity` | `quantity − quantityReturned` | لا مسار مرتجعات يغيّر `quantityReturned`؛ تبقى = الكمية كاملة. لا تبنِ واجهة مرتجعات عليه. |
| `OrderResponse.items[].assemblyTotal` | رسوم التركيب × الكمية على السطر | ضرب الرسوم في الكمية بتقريب مختلف. |
| `OrderResponse.scheduledDeliveryAt` مقابل `preferredDeliveryDate/Slot` | الأول موعد الموظف المتفق، الثاني رغبة العميل | عرض رغبة العميل كوعد. غياب `scheduledDeliveryAt` = لم يُتفق على موعد. |
| `shippingAddress.formatted` و`AddressResponse.formatted` | نص العنوان مُركَّب (بكلمات إنجليزية `building/floor/apt`) | تركيب العنوان بترتيب مختلف عن الذي طُبع على الفاتورة. |
| الهواتف في كل الاستجابات | تعود بصيغة محلية `01XXXXXXXXX` | إعادة تطبيع/مقارنة نصية مع الصيغة المخزَّنة `+20…`. |
| `code` في استجابة الخطأ | العميل يتفرّع على `code` | الاعتماد على نص `detail` (إنجليزي ويتغير). 15 كودًا معرّفة لا تُرمى أبدًا (1.6). |

المصدر: `src/main/java/com/velora/api/order/domain/CustomerOrder.java:259-261,285-287` · `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:119-122` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:286-288` · `src/main/java/com/velora/api/order/service/OrderService.java:397-465` · `src/main/java/com/velora/api/order/domain/FulfillmentStatus.java:76-79` · `src/main/java/com/velora/api/order/service/OrderService.java:392-394` · `src/main/java/com/velora/api/order/domain/OrderItem.java:121-123,112-114` · `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:29-108` · `src/main/java/com/velora/api/common/util/PhoneNormalizer.java:68-74` · `src/main/java/com/velora/api/common/exception/ErrorCode.java`

### 5.2 السلة والشحن

| الحقل | ما يقرره الخادم | الخطأ المحتمل لو حُسب محليًا |
|---|---|---|
| `CartResponse.warnings[]` (`PRICE_CHANGED`, `QUANTITY_REDUCED`, `OUT_OF_STOCK`, `PRODUCT_UNAVAILABLE`) | يُحسب عند كل قراءة من السعر والمخزون الحاليين | مقارنة `priceAtAdd` بسعر قديم مخزَّن في المتصفح؛ تحذيرات الكمية تعتمد المتاح اللحظي. |
| `CartResponse.checkoutReady` | `true` إن السلة غير فارغة ولا تحذير سوى `PRICE_CHANGED` | حظر الشراء عند تغيّر السعر (الخادم لا يحظر)، أو السماح بسلة فيها نفاد. |
| `CartItemResponse.unitPrice` / `priceAtAdd` / `priceChanged` | السعر الحالي هو ما يُحاسَب به؛ `priceAtAdd` معلومة للعرض فقط | عرض `priceAtAdd` كأنه ما سيُدفع. |
| `CartResponse.estimatedTotal` | `subtotal − discount + assembly` — **بدون شحن** | عرضه كإجمالي نهائي. الإجمالي بالشحن في `ShippingQuoteResponse.estimatedTotal`. |
| `CartResponse.taxIncluded` | ضريبة مستخرجة لكل سطر + من رسوم التركيب | جمع نسبة ثابتة 14% على الإجمالي؛ المعدل لكل متغيّر. |
| `CartResponse.discountTotal`, `couponCode` | `0` دائمًا؛ لا كوبونات | بناء واجهة كوبون. |
| `ShippingQuoteResponse.shippingCost`, `shippingCapApplied`, `uncappedCost`, `breakdown[]` | الشحن بالقطعة حسب الحجم والمنطقة ثم السقف | ضرب سعر واحد في عدد القطع، أو إهمال السقف، أو الحساب بالوزن. |
| `ShippingQuoteResponse.codFee` | رسوم COD للمنطقة، لا يطبّق عليها السقف | إدراجها داخل السقف. |
| `ShippingQuoteResponse.freeShippingApplied` | `shippingCost == 0` | افتراض عتبة شحن مجاني (الحقلان `freeShippingThreshold` و`amountToFreeShipping` دائمًا غائبان). |
| `ShippingQuoteResponse.deliveryDaysMin/Max` | تقدير المنطقة | حسابه من تاريخ الطلب؛ ولا يُستعمل للتحقق من `preferredDeliveryDate` عمدًا. |
| `ShippingQuoteResponse.estimatedTotal` | `subtotal + assembly + shipping + codFee` | نسيان رسوم التركيب أو COD. |
| `GovernorateResponse.served` (و`CustomRequestResponse.governorateServed`) | المحافظة في منطقة مسعَّرة | إخفاء المحافظات غير المخدومة (المقصود أن تظهر مع `served:false`). |

المصدر: `src/main/java/com/velora/api/cart/service/CartService.java:328-356,358-424` · `src/main/java/com/velora/api/cart/dto/CartWarning.java` · `src/main/java/com/velora/api/cart/dto/CartResponse.java` · `src/main/java/com/velora/api/cart/domain/CartItem.java:56-58` · `src/main/java/com/velora/api/shipping/service/ShippingService.java:103-145,65-95` · `src/main/java/com/velora/api/shipping/dto/ShippingQuoteResponse.java` · `src/main/java/com/velora/api/customrequest/service/CustomRequestAdminService.java:224-262`

### 5.3 الكتالوج

| الحقل | ما يقرره الخادم | الخطأ المحتمل |
|---|---|---|
| `ProductSummaryResponse.minPrice/maxPrice` | أدنى/أعلى سعر بين المتغيّرات **الفعّالة غير المؤرشفة** (حتى لو نفد مخزونها) | حسابها من المتغيّرات المتاحة فقط. |
| `ProductSummaryResponse.compareAtPrice`, `discountPercent` | من **أرخص متغيّر** فقط (نسبة مُقرَّبة `HALF_UP`) ؛ تغيب إن لم يكن خصم (`compareAt > price`) | حساب الخصم من متغيّر آخر أو من سعر الأعلى. |
| `ProductSummaryResponse.inStock`, `availableQty` | مجموع `onHand − reserved` عبر كل المتغيّرات الفعّالة | اعتبار المنتج متوفرًا لأن متغيّرًا واحدًا متوفر (والعكس)؛ الكمية الإجمالية لا تعني أن كل خيار متاح. |
| `ProductSummaryResponse.hoverImageUrl` | ثاني صورة بالترتيب، تغيب إن كانت صورة واحدة | — |
| `ProductDetailResponse.variants[]` (`availableQty`, `inStock`, `discountPercent`, `images`) | مصفوفة كاملة بالحالة اللحظية | الاعتماد عليها لمدة طويلة؛ التوفر الفعلي عبر `GET /variants/{id}/availability` (وصورها هنا هي المرتبطة بالمتغيّر فقط، والمشتركة في `images` على مستوى المنتج). |
| `ProductDetailResponse.variantOptions[]` | محاور الاختيار (الخصائص المحددة للمتغيّرات) | اشتقاقها من `attributeValueIds`. |
| `ProductDetailResponse.fulfillmentType` | يحدد قابلية الشراء | لا يوجد حقل `purchasable`؛ على العميل ألا يعرض "أضف للسلة" إلا لـ`READY_MADE` (والخادم يرفض غير ذلك بـ`PRODUCT_NOT_PURCHASABLE`). |
| `ProductDetailResponse.seo.canonicalPath` | `/p/<slug>` ثابت | — |
| `VariantController.availability.sellable` | `ACTIVE ∧ غير مؤرشف ∧ متاح>0` | **لا يفحص حالة المنتج ولا نوع التنفيذ**، فقد تُعاد `sellable:true` لمتغيّر منتج غير منشور أو غير `READY_MADE`. |
| `ProductAdminResponse.warnings[]` | تحذيرات إنجليزية (بلا متغيّرات/صور/ترجمة إنجليزية/نشر مع نفاد) | — |
| `VariantMatrixPreviewResponse.combinations[].alreadyExists`, `suggestedSku`, `warnings` | تفادي التكرار واقتراح SKU بالبادئة `sku-prefix` | توليد SKU محليًا يخالف البادئة المعتمدة. |

المصدر: `src/main/java/com/velora/api/catalog/mapper/CatalogMapper.java:50-77,79-114,118-144,274-280` · `src/main/java/com/velora/api/catalog/domain/Product.java:133,137,143` · `src/main/java/com/velora/api/catalog/domain/ProductVariant.java:129-137,120-122` · `src/main/java/com/velora/api/catalog/web/VariantController.java:34-45` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:443-508` · `src/main/java/com/velora/api/catalog/service/admin/VariantMatrixGenerator.java:41-97`

### 5.4 المخزون والأدمن

| الحقل | ما يقرره الخادم | الخطأ المحتمل |
|---|---|---|
| `InventoryAdminResponse.qtyAvailable`, `lowStock`, `outOfStock` | `onHand − reserved`؛ `lowStock = available ≤ minStockLevel` (يشمل النفاد)؛ `outOfStock = available ≤ 0` | استعمال `qtyOnHand` للتنبيه بدل المتاح. |
| `StoreProfileResponse.missingFields[]` | أي من `address, phone, email, taxNumber, commercialRegister` فارغ | افتراض أن الفاتورة مكتملة. |
| `RemittanceResponse.difference`, `status` | `received − expected`؛ `SHORT` إن سالب | اعتبار الزيادة (فرق موجب) خطأً؛ الخادم يسمّيها `SETTLED`. |
| `UnsettledOrderResponse.daysWaiting` | أيام منذ `deliveredAt` | حساب بمنطقة زمنية مختلفة. |
| `DashboardResponse.sales` | الإيراد = مجموع `grandTotal` للطلبات منذ بداية اليوم/الأسبوع (آخر 7 أيام)/الشهر **بحالة ≠ CANCELLED** (يشمل `PENDING` والمرفوضة والمرتجعة) — **ليس نقدًا محصَّلًا** | اعتباره دخلًا. |
| `DashboardResponse.codPosition` | طلبات COD المسلَّمة غير المسوّاة وأقدم أيامها | — |
| `DashboardResponse.actionQueues[]` | عدد الطلبات لكل حالة قيد العمل + `label` عربي + `actionPath` (مسار **واجهة الأدمن** لا الـ API) | بناء التسميات محليًا. |
| `DashboardResponse.alerts[]` | مرتبة بالخطورة (`HIGH\|MEDIUM\|LOW`)، **نصوصها عربية جاهزة** ومساراتها `actionPath` (بعضها يستعمل معاملات استعلام غير مدعومة في الـ API كـ`paymentStatus` في قائمة الطلبات) | ترجمتها أو إعادة ترتيبها. العتبات ثابتة في الكود (24 ساعة لطلب راكد، 48 ساعة لحجز عالق، 7 أيام لتحصيل COD، 80% لنجاح التوصيل مع ≥5 محاولات، وللطلبات الخاصة: ACCEPTED 7/14 يومًا، NEW يومان، CONTACTED 3 أيام، QUOTED 14 يومًا). |
| `DashboardResponse.deliveryHealth.successRatePercent` | `delivered ÷ (delivered + refused + returnedToSeller) × 100` لطلبات **وُضعت** هذا الشهر (حسب حالتها الحالية)؛ **`failed` و`cancelled` لا تدخل في المقام**؛ ويُنبَّه عند أقل من 80% مع `delivered + refused ≥ 5` | حسابها بإدخال المحاولات الفاشلة أو الملغاة. |
| `DashboardResponse.staleOrders` | طلبات (`PENDING\|CONFIRMED\|AWAITING_SCHEDULE\|PROCESSING`) لم يتغير `updated_at` لها منذ 24 ساعة، بحد 20؛ **يُستثنى منها ما له موعد تسليم مستقبلي** | اعتبار كل طلب قديم راكدًا. |
| `AuditLogResponse.actorName` | الاسم منسوخًا وقت الكتابة | الاستعلام عن اسم المستخدم الحالي. |

المصدر: `src/main/java/com/velora/api/inventory/domain/Inventory.java:91-93` · `src/main/java/com/velora/api/inventory/service/InventoryAdminService.java:244-264` · `src/main/java/com/velora/api/store/service/StoreProfileService.java:89-118` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:71-85,104-195` · `src/main/java/com/velora/api/dashboard/service/DashboardService.java:111-204,28,31,34,37` · `src/main/java/com/velora/api/dashboard/repository/DashboardQueries.java:40-48,94-108,151-173,255-287,371-382` · `src/main/java/com/velora/api/audit/service/AuditService.java:51-73`

## 6. الإعدادات المؤثرة على السلوك

ثلاث طبقات للإعدادات، ومن يملك تغيير كل منها مختلف:
1. **ملف `application.yml` + متغيرات البيئة** — يغيّرها المطوّر/المسؤول عن النشر ويتطلب **إعادة تشغيل**؛ لا واجهة إدارية لها.
2. **قاعدة البيانات عبر API الأدمن** — يغيّرها الأدمن في وقت التشغيل بأثر فوري على الطلبات الجديدة.
3. **ثوابت في الكود** — تتطلب تعديل الكود ونشرًا.

### 6.1 الهوية والبادئات (`velora.branding.*`) — الطبقة 1

| الإعداد | القيمة الحالية | ماذا يتأثر عند تغييره |
|---|---|---|
| `order-number-prefix` | `RYL` | بادئة أرقام الطلبات **الجديدة** `RYL-yyMMdd-NNNN`. الأرقام المخزَّنة لا تُعاد كتابتها. قيد `uq_order_number` فريد على الرقم الكامل. |
| `invoice-number-prefix` | `RYL-INV` | بادئة أرقام الفواتير الجديدة. **عدّاد الفواتير لا يتصفّر** (مفتاحه السنة)، فتبقى الأرقام متسلسلة عبر تغيير البادئة. |
| `brand-name` | `ROYAL` | احتياطي فقط: علامة الفاتورة الاسمية (`wordmark`) وبريد التأكيد وعنوان Swagger. **يتقدّم عليها الاسم القانوني في ملف المتجر** إن كان غير فارغ. كما تُستعمل لإنشاء ملف المتجر الافتراضي إن لم يوجد صفه. |
| `sku-prefix` | `RYL` | بادئة اقتراحات SKU الجديدة فقط في معاينة المصفوفة؛ الـ SKU المخزَّن لا يتغير ولا يُعاد استخدامه. |

لاحظ ما **ليس** قابلًا للضبط: اسم الملف المصدَّر `velora-orders-…`/`velora-picking-…`، واسم الخدمة `velora-api` في `/ping`، وأسماء أمثلة Swagger تحمل `VELORA/VLR`.

المصدر: `src/main/java/com/velora/api/common/config/BrandingProperties.java` · `src/main/resources/application.yml:133,134,131,137` · `src/main/java/com/velora/api/order/service/OrderNumberGenerator.java:44-66` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:407-409` · `src/main/java/com/velora/api/catalog/service/admin/VariantMatrixGenerator.java:144` · `src/main/java/com/velora/api/store/service/StoreProfileService.java:39-49` · `src/main/java/com/velora/api/common/web/PingController.java:23-29`

### 6.2 ملف المتجر `store_profile` — الطبقة 2 (الأدمن)

- صف وحيد (`id = 1`، قيد `ck_store_profile_single_row`). يُنشأ تلقائيًا عند أول حاجة بقيمة احتياطية إن غاب.
- يعدّله الأدمن بـ `PUT /api/v1/admin/settings/store-profile` (استبدال كامل — 4.4). الحقول: `legalName*, legalNameEn, address, phone, email, taxNumber, commercialRegister, website, invoiceFooterNote`.
- **أثره:** يسري على الفواتير التي تصدر **بعد** التعديل (البائع يُنسخ على الفاتورة)، **باستثناء `invoiceFooterNote`** الذي يُقرأ حيًّا عند كل طباعة (2.1). كما يحدد الاسم الظاهر في بريد التأكيد. الحقول الفارغة تُحذف من الفاتورة بدل طباعة سطر فارغ. `taxNumber` يُقبل فارغًا أو `###-###-###`؛ طالما فارغ تُصدر الفواتير بلا رقم ضريبي. لا يُسجَّل في سجل التدقيق.

المصدر: `src/main/java/com/velora/api/store/service/StoreProfileService.java:39-49,58-77` · `src/main/java/com/velora/api/store/dto/StoreProfileRequest.java` · `src/main/resources/db/baseline/baseline_through_V14.sql:1382` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:100-102`

### 6.3 الضريبة

| ما | أين | من يغيّره | الأثر |
|---|---|---|---|
| معدل ضريبة كل متغيّر `taxRate` | عمود `product_variant.tax_rate` (`DECIMAL(5,4)`) | الأدمن عبر `POST /admin/products/{id}/variants` (حقل `taxRate`، اختياري عند التعديل) | يُنسخ على سطر الطلب وقت الشراء؛ تغييره لا يمس الطلبات القائمة ولا الفواتير الصادرة (اختبار `TaxSnapshotIntegrationTest`). الافتراضي عند إنشاء متغيّر بلا معدل 14% ثابت في الكود `ProductVariant.DEFAULT_TAX_RATE`. |
| ~~`velora.business.default-tax-rate`~~ و~~`prices-include-tax`~~ | **حُذفا من `application.yml`** (main وtest وexample) | — | كانا بلا أي كود يقرؤهما فلم يكن لتغييرهما أثر. لا يوجد الآن أي إعداد ضريبة: الـ14% ثابت في الكود، والأسعار شاملة الضريبة بحكم الكود (لا يمكن تحويلها بإعداد). تغيير الـ14% الافتراضي تعديل كود (ويستحسن مواءمة `df_var_tax` في قاعدة البيانات). |
| هل الشحن/COD/التركيب خاضع للضريبة | ثابت في `CheckoutService.calculateTotals` | تعديل كود | الشحن وCOD غير خاضعين؛ التركيب خاضع بمعدل السطر. |

المصدر: `src/main/java/com/velora/api/catalog/domain/ProductVariant.java:75,78` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:180,216-218` · `src/main/java/com/velora/api/order/service/CheckoutService.java:310-366`

### 6.4 الشحن — الطبقة 2 (الأدمن)

| ما | أين يعيش | كيف يُغيَّر | الأثر |
|---|---|---|---|
| المناطق الست وأسماؤها | `shipping_zone` (مبذورة بالـ seed) | **لا يوجد API لإنشاء/إعادة تسمية/تعطيل منطقة** (SQL فقط) | — |
| سعر القطعة لكل (منطقة × حجم) | `shipping_rate.base_cost` | `PUT /admin/shipping/rates` | يسري فورًا على كل عروض الأسعار والطلبات **الجديدة**؛ الطلبات القائمة تحتفظ بلقطتها. تُسجَّل في التدقيق. |
| رسوم COD وأيام التسليم (min/max) للمنطقة | تتكرر في كل صفوف المنطقة | نفس الـ endpoint (`codFee`, `deliveryDaysMin/Max`) | تُكتب على كل أحجام المنطقة. |
| سقف الشحن للمنطقة | `shipping_zone.max_shipping_cost` (قد يكون `null` = بلا سقف) | `PUT /admin/shipping/zones/{zoneId}/max-shipping-cost` | يحدد أعلى شحن بصرف النظر عن عدد القطع؛ لا يشمل COD. قيمة seed: 1500 لكل المناطق. |
| أي محافظة في أي منطقة | `shipping_zone_governorate` | `PUT/DELETE /admin/shipping/governorates/{id}/zone` | **فتح/إغلاق التوصيل** لمحافظة: يغيّر ما إذا كان العميل يستطيع إنشاء عنوان وطلب فيها. الإغلاق لا يمس الطلبات القائمة. seed: جنوب سيناء وأسوان والأقصر مغلقة. فتح محافظة في منطقة غير مسعَّرة لكل الأحجام الثلاثة مرفوض. |
| المحافظات الـ27 | `governorate` | SQL فقط | — |
| حجم الطرد لكل منتج | `product.shipping_size_class` | `PUT /admin/products/{id}` | منتج بلا حجم (غير READY_MADE) يرفض إتمام الطلب إن دخل السلة. |

- قيم الـ seed للمرجع (قد تختلف في قاعدة البيانات الحية): الأسعار بالجنيه للقطعة — `GREATER_CAIRO` 0/0/0، `ALEXANDRIA` 120/250/450، `CANAL` 120/250/450، `DELTA` 100/200/400، `UPPER_EGYPT` 150/300/550، `REMOTE` 200/400/700 (صغير/متوسط/كبير)؛ `cod_fee` صفر في كل الصفوف؛ التسليم 1–3 أيام للقاهرة الكبرى و2–4 للإسكندرية والدلتا والقناة و2–5 للصعيد و3–8 للنائية. هذه الأرقام من `seed_reference_data.sql` وليست مقروءة من القاعدة الحية.
- أعمدة قديمة (`max_weight_grams`, `cost_per_extra_kg`, `free_shipping_over`) ما زالت في الجدول ولا يقرؤها الحساب.

المصدر: `src/main/java/com/velora/api/shipping/service/ShippingAdminService.java:108-178,182-201,251-287,294-310` · `src/main/java/com/velora/api/shipping/web/AdminShippingController.java` · `src/main/java/com/velora/api/shipping/service/ShippingCalculator.java:47-87` · `src/main/resources/db/baseline/seed_reference_data.sql:59-69,74-96,101-131` · `src/main/java/com/velora/api/shipping/service/ShippingCalculator.java:28`

### 6.5 المخزون والتنبيهات والمهام المجدولة

| ما | أين | من يغيّره | الأثر |
|---|---|---|---|
| `minStockLevel` لكل متغيّر (الافتراضي 3) | `inventory.min_stock_level` | يُحدَّد **عند إنشاء المتغيّر فقط**؛ لا endpoint لتعديله لاحقًا | يحدد `lowStock` وتقرير النقص ولوحة التحكم (`available ≤ min`). |
| `velora.business.reservation-ttl-minutes` | `application.yml` (20) | تعديل + إعادة تشغيل | مدة صلاحية حجز بلا طلب. **عمليًا بلا أثر في مسار الشراء الحالي** (2.8). |
| عتبات لوحة التحكم | ثوابت `DashboardService` | تعديل كود | 24 ساعة لراكد، 48 ساعة لحجز عالق، 7 أيام لتأخر تحصيل COD، 80% لنجاح التوصيل، وعتبات الطلبات الخاصة (5.4). |
| دورية مهام الخلفية | ثوابت: تحرير الحجوزات المنتهية كل دقيقة (بعد تأخير 30ث، دفعة 200)؛ تنظيف حدود المعدل كل 10 دقائق؛ تنظيف Idempotency يوميًا 03:30 | تعديل كود | — |
| حدود المعدل | `velora.rate-limit.*` | تعديل + إعادة تشغيل | القيم في 4.6. الحدّان 5/ساعة للـ OTP لكل وجهة والنافذة ثابتتان في الكود. |

المصدر: `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:267-296` · `src/main/java/com/velora/api/inventory/domain/Inventory.java:65` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:44,43` · `src/main/java/com/velora/api/dashboard/service/DashboardService.java:28` · `src/main/java/com/velora/api/inventory/service/ReservationCleanupJob.java:30-47,21` · `src/main/java/com/velora/api/common/ratelimit/RateLimiter.java:50-58` · `src/main/java/com/velora/api/idempotency/service/IdempotencyCleanupJob.java:28-38` · `src/main/resources/application.yml:99`

### 6.6 الأمان والرموز

| ما | القيمة/المصدر | الأثر |
|---|---|---|
| `JWT_SECRET` (متغير بيئة) | **إلزامي، 64 حرفًا على الأقل** وإلا يفشل التشغيل (`IllegalStateException`) | تغييره يبطل كل الـ access tokens الصادرة. |
| `velora.jwt.access-token-minutes` / `refresh-token-days` | 30 دقيقة / 30 يومًا | مدة الجلسة. |
| `GUEST_TOKEN_SECRET` | اختياري، يرجع إلى `JWT_SECRET`؛ ≥32 حرفًا | تغييره يبطل رموز الضيف (ويبطل رموز مرفقات الطلبات الخاصة لأنها تستعمل نفس السر). |
| `velora.guest-token.accept-legacy-unsigned` | **`true` حاليًا** | يقبل رموز الضيف القديمة غير الموقّعة مع تحذير في اللوغ؛ يعطّل عمليًا حماية السلة بالتوقيع. تغييره إلى `false` يُخرج السلال القديمة. |
| `velora.cors.allowed-origins` | `http://localhost:*,http://127.0.0.1:*,http://192.168.*:*` | مسموح مع الاعتماديات (`allowCredentials`). **يجب تغييره للإنتاج**، وإلا تُرفض نطاقات الواجهة الحقيقية. |
| `velora.custom-request.attachment-token-hours` | 24 | صلاحية رمز رفع صور الطلب الخاص. |
| قوة BCrypt | 12 ثابتة في الكود | — |

المصدر: `src/main/java/com/velora/api/identity/security/JwtService.java` · `src/main/java/com/velora/api/cart/security/GuestTokenService.java` · `src/main/java/com/velora/api/customrequest/service/CustomRequestTokenService.java` · `src/main/java/com/velora/api/common/security/SecurityConfig.java:157-172,153` · `src/main/resources/application.yml:98,96,87,88,128`

### 6.7 التخزين والقوالب والبريد والبنية

| ما | أين | الأثر |
|---|---|---|
| `velora.storage.local-path` | `D:/ibrahim_watches/royal-uploads` | مكان الصور وPDF الفواتير على القرص؛ يفقدها إعادة بناء الحاوية. التخزين محلي فقط (لا S3/R2). مجلد `invoices/` تحته يُكتب لكنه **مغلق أمام HTTP** (7.5/2.9). |
| `velora.storage.public-url-prefix` | `http://localhost:8081/uploads` | يُبنى منه كل رابط ملف. صور المنتجات تُخزَّن كمفاتيح وتُبنى روابطها عند القراءة (تتبع تغيير البادئة)، **لكن `order_item.image_url` يُخزَّن رابطًا كاملًا وقت الشراء** فتبقى صور الطلبات القديمة على البادئة القديمة. |
| `velora.storage.max-file-size-bytes` | 5 MB (والأنواع المسموحة ثابتة في الكود) | حد رفع الصور؛ و`spring.servlet.multipart` 5MB/10MB حدّ آخر مستقل. |
| `spring.mail.*` (Gmail SMTP 587 + `MAIL_USERNAME`/`MAIL_PASSWORD`) | `application.yml` + البيئة | بدون بيانات اعتماد يعمل النظام لكن لا يصل بريد التأكيد (الفشل يُسجَّل فقط). الحد المعلَّق 500 رسالة/يوم (حد Gmail). البريد الوحيد المُرسَل هو تأكيد الطلب. |
| قوالب `invoice.html`, `picking-list.html`, `order-confirmation.html` وخطوط Amiri | `src/main/resources` | تصميم الفاتورة وقائمة التجهيز والبريد؛ تغييرها تعديل كود ونشر. بدون ملفي الخط تظهر الأحرف العربية مربعات في الـ PDF. |
| `spring.datasource.url` (`databaseName=royal`) ، `hikari.maximum-pool-size: 10` | `application.yml` | قاعدة البيانات الحية `royal`؛ الاختبارات تعمل على `royal_test` فقط. |
| `spring.jpa.hibernate.ddl-auto: validate` | `application.yml` | المخطط تملكه سكربتات SQL؛ إضافة عمود في الكود دون سكربت يفشل التشغيل. |
| `server.port: 8081`, `forward-headers-strategy: framework` | `application.yml` | الروابط المولَّدة خلف proxy تتبع رؤوس `X-Forwarded-*`. |
| `spring.jackson.default-property-inclusion: non_null` | `application.yml` | يحذف `null` من كل الاستجابات (2.5). |
| إنشاء أدمن | لا مسار في الكود (التسجيل يمنح `CUSTOMER` فقط) | الدور `ADMIN` يُمنح بإدخال مباشر في قاعدة البيانات (`user_role`). عمود `is_staff` منفصل عن الدور: يُستعمل فقط لاستثناء حسابات الموظفين من إحصاءات العملاء، **ولا يعتمد عليه التفويض**. |

المصدر: `src/main/java/com/velora/api/common/storage/StorageProperties.java` · `src/main/java/com/velora/api/order/service/CheckoutService.java:292` · `src/main/java/com/velora/api/notification/service/MailService.java` · `src/main/resources/application.yml:1-26,11-19,80-83` · `src/main/java/com/velora/api/export/service/PdfRenderer.java:47` · `src/main/java/com/velora/api/identity/service/AuthService.java:85-119` · `src/main/java/com/velora/api/customer/repository/CustomerQueries.java:61` · `src/main/resources/db/baseline/seed_reference_data.sql:12-20`

## 7. الحدود وما لا يدعمه النظام عمدًا

بصراحة: النظام اليوم **متجر بدفع عند الاستلام فقط** بإدارة يدوية. كل ما يلي مؤكد بغياب الكود المقابل (بحث في `src/main/java` بعد قراءة المسارات ذات الصلة).

### 7.1 ما لا يستطيع النظام فعله اليوم

**المال والدفع**
- لا دفع إلكتروني (بطاقة/محفظة/فوري): القيم موجودة في `PaymentMethod` فقط، والـ checkout يرفضها.
- لا استرداد أموال ولا إشعارات دائنة ولا مبلغ استرداد. `PARTIALLY_REFUNDED/REFUNDED` تسميات يدوية.
- لا خصومات ولا كوبونات ولا ترويج: `discountTotal` صفر دائمًا، وحقل `couponCode` بلا endpoint يضبطه.
- لا إشعارات دائنة: تصحيح فاتورة = إلغاؤها ثم إصدار بديلة برقم جديد (الملغاة تبقى برقمها، 3.4). والتسوية الملغاة يمكن إعادة تسجيلها للطلبات نفسها (3.5).
- عملة واحدة `EGP` (قيمة افتراضية في الكيان؛ لا يوجد إدخال للعملة).

**الطلبات والمخزون**
- لا مرتجعات: لا طلب إرجاع ولا فحص ولا إعادة تخزين. الحالتان `RETURNED`/`PARTIALLY_RETURNED` يضعهما الأدمن يدويًا بلا أثر مخزون أو مال؛ `quantityReturned` لا يتغير أبدًا. إعادة البضاعة إلى الرصيد تتم بتعديل مخزون يدوي (`MANUAL_ADJUSTMENT`).
- لا تعديل لطلب (بنوده أو عنوانه) بعد إنشائه، ولا دمج/تقسيم طلبات، ولا شحن جزئي.
- لا طلب مسبق/متابعة عند نفاد المخزون؛ النفاد يفشل الإضافة أو الـ checkout.
- مستودع واحد (`location_code = 'MAIN'`).
- لا تتبع شحنة (لا رقم تتبع، ولا ربط بشركة شحن؛ جدول `shipment` بلا كود).
- لا مصدر لتحويل طلب خاص إلى طلب بيع.

**العملاء والحساب**
- OTP وإعادة تعيين كلمة المرور **لا يصلان لأحد**: الرمزان يُولَّدان ويُخزَّنان ولا يُرسلان بأي قناة (`TODO(notification module)`)، ولا يُسجَّلان. لذلك لا يمكن لعميل نسي كلمة مروره استردادها، ولا يمكن إتمام تحقق الهاتف/البريد.
- التسجيل لا يشترط تحققًا؛ `emailVerified/phoneVerified` تُضبط فقط بنجاح `otp/verify` (الذي لا يمكن استلام رمزه).
- لا تعديل للملف الشخصي، ولا تغيير كلمة المرور داخل الجلسة، ولا تغيير هاتف/بريد، ولا حذف حساب. `UserStatus` لا يغيّره أي endpoint.
- لا إدارة مستخدمين أو أدوار من الـ API؛ الأدمن يُنشأ بإدخال في القاعدة. كل الأدمن بصلاحية كاملة.
- لا استعلام عن طلب ضيف بعد الشراء، ولا ربط طلبات الضيف بحساب يُنشأ لاحقًا (بالهاتف نفسه).
- لا مراجعات منتجات ولا قوائم أمنيات ولا إشعار "عاد للمخزون".

**الإشعارات**
- البريد الوحيد المُرسَل: تأكيد الطلب (إن وُجد بريد). لا بريد/رسالة عند تغيّر الحالة أو الشحن أو التسليم أو الفاتورة أو الطلب الخاص أو عرض السعر. لا SMS إطلاقًا.

**الكتالوج والبحث**
- البحث على الاسم والوصف القصير فقط وبلغة الطلب؛ لا ترتيب بالأكثر مبيعًا (`best_selling` مؤجَّل بتعليق TODO).
- تغيير `slug` منتج لا يكتب إعادة توجيه (التعليق `TODO(SEO)`؛ جدول `url_redirect` بلا كود)، ولا يوجد إعادة توجيه لـ`slug` المعرض.
- الصور: لا مصغّرات تُولَّد (`thumbUrl` لا يُملأ أبدًا فيغيب من الاستجابة).
- لا حذف نهائي لأي شيء ولا إلغاء أرشفة لمتغيّر؛ `VariantStatus.INACTIVE` لا يضعه أحد.
- لا endpoint لتعديل `minStockLevel` بعد إنشاء المتغيّر، ولا لتعديل المخزون عبر تعديل المتغيّر.
- الأدمن: لا بحث/فلترة في `GET /admin/products`، ولا بحث بـ**رقم الطلب** في `GET /admin/orders` (هاتف أو حالة فقط)، ولا فلتر حالة الدفع في قائمة الطلبات (متاح في التصدير فقط)، ولا بحث برقم الفاتورة.

**الشحن**
- لا إنشاء/تعطيل مناطق شحن من الـ API (SQL)، ولا تسعير بالمدينة أو الرمز البريدي أو الوزن، ولا شحن مجاني بعتبة (أُزيل)، ولا أسعار حية من شركة شحن.
- حجوم الطرد ثلاثة ثابتة (`SMALL|MEDIUM|LARGE`) وقيد CHECK في القاعدة.

**التشغيل**
- لا انتهاء لسلة الضيف ولا تنظيف لها؛ `ABANDONED` بلا مُنفِّذ.
- حدود المعدل في ذاكرة العملية: تُصفَّر عند إعادة التشغيل ولا تعمل عبر أكثر من نسخة. تخزين الملفات محلي.
- ملاحظة منهجية: كل ما في هذا الملف مُستخرج من قراءة الكود والسكربتات؛ لم أشغّل التطبيق ولا الاختبارات ولا اتصلت بقاعدة البيانات الحية.

المصدر: `src/main/java/com/velora/api/order/domain/PaymentMethod.java` · `src/main/java/com/velora/api/order/service/CheckoutService.java:469-485,324` · `src/main/java/com/velora/api/order/domain/CustomerOrder.java:81` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:100-102` · `src/main/java/com/velora/api/order/service/OrderStatusMachine.java:29-108` · `src/main/java/com/velora/api/order/domain/OrderItem.java:107` · `src/main/java/com/velora/api/inventory/domain/Inventory.java:56` · `src/main/java/com/velora/api/identity/service/OtpService.java:68-108` · `src/main/java/com/velora/api/identity/service/AuthService.java:223-245` · `src/main/java/com/velora/api/identity/web/AuthController.java:131-134` · `src/main/java/com/velora/api/notification/service/MailService.java` · `src/main/java/com/velora/api/notification/service/OrderConfirmationListener.java:56-81` · `src/main/java/com/velora/api/catalog/service/ProductQueryService.java:125-136` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:107-147` · `src/main/java/com/velora/api/catalog/service/admin/ProductImageService.java:139-150` · `src/main/java/com/velora/api/catalog/domain/VariantStatus.java` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:198-241` · `src/main/java/com/velora/api/order/service/OrderService.java:107-122` · `src/main/java/com/velora/api/order/repository/OrderRepository.java` · `src/main/java/com/velora/api/cart/domain/CartStatus.java` · `src/main/java/com/velora/api/common/ratelimit/RateLimiter.java` · `src/main/java/com/velora/api/common/storage/LocalStorageService.java`

### 7.2 ثوابت في الكود تبدو قابلة للضبط

| الثابت | القيمة | ملاحظة |
|---|---|---|
| معدل الضريبة الافتراضي (14%) | ثابت مسمّى `ProductVariant.DEFAULT_TAX_RATE` | **لا إعداد له**: المفتاحان المضلِّلان `velora.business.default-tax-rate` و`prices-include-tax` حُذفا. الأسعار شاملة الضريبة بحكم الكود دائمًا. |
| الشحن ورسوم COD خارج الضريبة | منطق في `calculateTotals` | تعديل كود. |
| مدة OTP (10 د) وعدد طلباته لكل وجهة (5/ساعة) وعدد محاولاته (5) | ثوابت | الحدّ لكل IP فقط من الـ yml. |
| مدة بقاء مفتاح Idempotency | 24 ساعة | ثابت. |
| سقف السلة (50 سطرًا)، كمية السطر (1–99)، العناوين (10)، الصور (20/20/5)، تاريخ التسليم المفضل (90 يومًا) | ثوابت | — |
| سقف الصفحة العامة للمنتجات (60)، مرتبطة (8)، تصدير (5000/300) | ثوابت | — |
| عتبات لوحة التحكم ونصوص تنبيهاتها (عربية) | ثوابت | — |
| قوة BCrypt (12)، طول كلمة المرور (8–72)، أنواع الصور المسموحة | ثوابت | — |
| اللغات المدعومة `ar`,`en` | `LocaleResolver` و`pattern` في `TranslationRequest` | إضافة لغة تعديل كود وقيود DB. |
| الهاتف: موبايل مصري فقط (010/011/012/015) | `PhoneNormalizer` | لا يقبل أرقامًا دولية أو أرضية. |
| الأدوار `CUSTOMER` و`ADMIN` | seed + `Role` | — |
| المحافظات (27) | seed | — |
| عملة `EGP` | الكيان | — |
| سنة الفاتورة | `LocalDate.now()` بتوقيت **الخادم** (لا القاهرة) | حول منتصف ليل 31 ديسمبر قد تُرقَّم فاتورة بسنة مختلفة عن توقيت القاهرة إن اختلف توقيت الخادم. |
| "اليوم" في لوحة التحكم وتاريخ التسوية الافتراضي ومرجع التسوية | توقيت الخادم | بينما التحقق من تاريخ التسليم المفضل والطلبات الخاصة بتوقيت القاهرة. |
| اسم الخدمة `velora-api`، واسما ملفي التصدير | ثوابت | — |
| تعليق كود يقول "رسوم التركيب صفر حتى يؤكّد المحاسب" | **الواقع:** الرسوم تُحتسب فعليًا متى ضُبط `requiresAssembly=true` و`assemblyFee>0` على المنتج | لا تعتمد على التعليق. |

المصدر: `src/main/java/com/velora/api/catalog/domain/ProductVariant.java:75` · `src/main/java/com/velora/api/identity/service/OtpService.java:38,37` · `src/main/java/com/velora/api/idempotency/service/IdempotencyService.java:43` · `src/main/java/com/velora/api/cart/service/CartService.java:49` · `src/main/java/com/velora/api/catalog/service/ProductQueryService.java:35,36` · `src/main/java/com/velora/api/export/service/OrderExportService.java:48,49` · `src/main/java/com/velora/api/dashboard/service/DashboardService.java:28` · `src/main/java/com/velora/api/catalog/web/LocaleResolver.java:19` · `src/main/java/com/velora/api/common/util/PhoneNormalizer.java:22` · `src/main/java/com/velora/api/invoice/service/InvoiceService.java:134` · `src/main/java/com/velora/api/remittance/service/RemittanceService.java:279-283` · `src/main/java/com/velora/api/dashboard/service/DashboardService.java:51` · `src/main/java/com/velora/api/order/service/CheckoutService.java:73,344,341` · `src/test/java/com/velora/api/order/OrderAssemblyFeeIntegrationTest.java:184`

### 7.3 قابل للضبط ويبدو ثابتًا

- أسعار الشحن لكل قطعة وسقف الشحن ورسوم COD وأيام التسليم وفتح/إغلاق المحافظات — كلها بيانات يغيّرها الأدمن في وقت التشغيل بلا نشر (6.4).
- بيانات البائع القانونية ونص تذييل الفاتورة (6.2).
- معدل الضريبة لكل متغيّر ورسوم التركيب لكل منتج.
- بادئات الأرقام واسم العلامة (إعادة تشغيل).
- مدة الجلسات وحدود المعدل وقبول رموز الضيف غير الموقّعة وأصول CORS ومدة رمز مرفقات الطلب الخاص.
- مدة حجز المخزون (لكنها بلا أثر فعلي، 2.8).

المصدر: `src/main/java/com/velora/api/shipping/service/ShippingAdminService.java:108-178,182-201,251-287` · `src/main/java/com/velora/api/store/service/StoreProfileService.java:58-77` · `src/main/java/com/velora/api/catalog/service/admin/VariantAdminService.java:180` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:126` · `src/main/resources/application.yml:99,96,98,133`

### 7.4 ما يتوقعه العميل ولن يجده

1. **قراءة حالة طلبه الخاص** — لا endpoint؛ يعرف فقط رقم الطلب.
2. **استعادة كلمة المرور** أو استلام رمز تحقق — لا يصل شيء.
3. **تتبع طلب كضيف** أو معاينته بعد الشراء.
4. **الدفع الإلكتروني أو استرداد المبلغ**.
5. **تعديل الطلب أو عنوانه** بعد التأكيد، وكل ما بعد التسليم (إرجاع/استبدال).
6. **شحن مجاني بعتبة** أو كوبون خصم.
7. **إشعارات** عند الشحن أو التسليم.
8. **بحث في وصف المنتج الكامل أو العلامة أو التصنيف**.
9. **تحويل عرض السعر المقبول إلى طلب** — يبقى كل شيء يدويًا خارج النظام بعد `ACCEPTED`.
10. **تذكّر السلة بين الأجهزة كضيف** — رمز الضيف يعيش في متصفح واحد؛ الدمج يتم عند الدخول فقط.

المصدر: `src/main/java/com/velora/api/customrequest/web/CustomRequestController.java` · `src/main/java/com/velora/api/identity/web/AuthController.java` · `src/main/java/com/velora/api/order/web/OrderController.java` · `src/main/java/com/velora/api/customrequest/service/CustomRequestAdminService.java:108-137` · `src/main/java/com/velora/api/cart/service/CartService.java:172-225`


### 7.5 قرارات مقصودة وحدود معروفة

هذه ليست أخطاء لم تُكتشف؛ بعضها قرارات اتُّخذت عمدًا وبعضها عيوب معروفة لم تُعالَج بعد. لا تبنِ وثيقة المستخدم على افتراض غير ما هو مكتوب هنا.

- **المنتج المؤرشف يحتفظ بالـ `slug` الخاص به للأبد (قرار مقصود).** القيد `uq_prod_slug` فريد على كل صفوف `product` بما فيها المؤرشفة، وفحص التطبيق صار يوافقه (`existsBySlugAndIdNot`). النتيجة: منتج جديد باسم منتج مؤرشف يحصل على `<slug>-2` ولا يرث رابط القديم، ولا يُرفض بـ409 كما كان يحدث حين كان الفحص يتجاهل المؤرشف. السبب: رابط المنتج القديم قد يكون محفوظًا في مفضّلات العملاء ونتائج البحث وروابط مشاركة، وإعادة إعطائه لمنتج مختلف تجعل تلك الروابط تعرض منتجًا آخر؛ ولا توجد آلية إعادة توجيه (جدول `url_redirect` موجود بلا كود) لتخفيف ذلك. تغيير القرار يتطلب: فهرس مرشَّح `WHERE archived_at IS NULL` على `slug`، ومعالجة تصادم السلاج عند إلغاء الأرشفة (`publish` يمسح `archivedAt`)، وقرارًا بشأن إعادة التوجيه من الروابط القديمة.
- **صور الطلبات الخاصة عامة (قرار مقصود).** الملفات تحت `/uploads/custom-requests/` تُخدَم عبر `GET` بلا أي مصادقة، وحمايتها الوحيدة أن اسم الملف `UUID` عشوائي (`custom-requests/<yyyy>/<MM>/<uuid>.<ext>`) غير قابل للتخمين. **كل من يحصل على رابط صورة يستطيع فتحه إلى أجل غير مسمّى** — لا انتهاء ولا إبطال ولا ربط بالمستخدم. الروابط تظهر لمن يقرأ الطلب الخاص (الأدمن) وفي ردّ رفع المرفق للمتصل الذي رفعه. (صور المنتجات والتصنيفات والمعرض عامة بطبيعتها؛ أما PDF الفواتير فمغلقة — 2.9.)
- **ملف غير موجود تحت `/uploads/` يعيد 500 بدل 404 (عيب معروف).** `GlobalExceptionHandler` يعالج `NoHandlerFoundException` فقط، فيمرّ `NoResourceFoundException` الصادر من معالج الملفات الثابتة إلى المعالج الشامل: يُسجَّل ERROR ويعود `INTERNAL_ERROR` 500. الأثر: رابط صورة محذوفة أو خاطئ يظهر كخطأ خادم في اللوغ والمراقبة. لم يُصلح لأن الإصلاح يغيّر شكل استجابة ولم يُطلب.
- **وثائق المشروع المرجعية متقادمة:** `docs/VELORA_API_Contract.md` (الأسطر 1454–1455) ما زال يقول إن `ORDER_CANNOT_BE_CANCELLED` = «الطلب شُحن أو أُلغي سلفًا» وإن `INVALID_STATUS_TRANSITION` «احتياطي»، بينما الإلغاء مسموح الآن من أربع حالات فقط وكل رفض إلغاء يعيد `ORDER_CANNOT_BE_CANCELLED` (3.1). والملف `docs/VELORA_Backend_Blueprint.md` **مذكور في `CLAUDE.md` (السطر 97) وغير موجود** في المستودع (المجلد `docs/` فيه `ROYAL_CHANGES.md` و`VELORA_API_Contract.md` فقط). وثائق أخرى لم أراجعها.

المصدر: `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:389-414` · `src/main/java/com/velora/api/catalog/repository/ProductRepository.java:29` · `src/main/java/com/velora/api/catalog/service/admin/ProductAdminService.java:157-173` · `src/main/resources/db/baseline/baseline_through_V14.sql:959-961` · `src/main/java/com/velora/api/common/security/SecurityConfig.java:107` · `src/main/java/com/velora/api/customrequest/service/CustomRequestService.java:58` · `src/main/java/com/velora/api/common/storage/LocalStorageService.java:48-76` · `src/main/java/com/velora/api/common/config/StorageConfig.java:31-51` · `src/main/java/com/velora/api/common/exception/GlobalExceptionHandler.java:251-254,275-278` · `CLAUDE.md:97` · `docs/VELORA_API_Contract.md:1454-1455`

## 8. غير مُتحقَّق منه

لكل بند: **ما الذي شككتُ فيه ← أين بحثتُ ← لماذا لم أستطع الجزم.** لا تُعامَل هذه البنود كحقائق في الوثيقة النهائية.

1. **ما هي فئة المنتجات التجارية؟** بحثتُ في الكود والتعليقات والسكربتات (`grep -i furniture|أثاث|كنب|sofa`) وفي `CLAUDE.md` وملفات الـ seed. تعليقات الكود تذكر "furniture" في ثلاثة مواضع، بينما `CLAUDE.md` يذكر ساعات ومحافظ وعطورًا (من مشروع VELORA)، وملفات الـ seed لا تحوي أي تصنيفات أو منتجات. لا دليل قاطع على ما يُباع فعلًا في `royal`.
2. **حالة قاعدة البيانات الحية `royal`:** هل طُبِّق `V18__custom_request_completed.sql` (README يقول «Not yet applied»)؟ و`V19__replaceable_invoice_and_settlement.sql` **لم يُطبَّق على `royal`** (طُبِّق على `royal_test` فقط، مرتين، وأُثبتت قابليته للإعادة). وهل قيم الشحن الحية تطابق ملف seed؟ وهل يوجد مستخدم `ADMIN` وكيف أُنشئ؟ ما مستوى التوافق (`compatibility_level`) لـ`royal`؟ (على `royal_test` 170؛ على الحية مجهول، وهو شرط لعمل الفهارس المرشَّحة مع الاتصال الذي `ARITHABORT` فيه OFF — 3.4/3.5.) وكم فاتورة وتسوية ملغاة فيها الآن؟ لم أتصل بقاعدة البيانات الحية؛ أرقام 6.4 من `seed_reference_data.sql` لا من القاعدة.
3. **تعريض endpoints الـ actuator:** `/actuator/health` و`/actuator/info` مسموحان في الأمان، لكن لم أجد `management.endpoints.web.exposure.*` في `application.yml` (الافتراضي في Spring قد لا يعرّض `info`). لم أتحقق.
4. **الحد الأقصى لحجم الصفحة** للـ endpoints التي بلا سقف في الكود (قوائم الأدمن، `GET /me/orders`، التسويات، التدقيق...): لا يوجد `spring.data.web.pageable.max-page-size` في الـ yml، فالمرجح أن يسري الحد الافتراضي في Spring Data (2000)، لكني لم أتحقق من النسخة المستعملة.
5. **Deadlock في `reserveAll`:** التعليق يقول إن ترتيب المتغيّرات "المتسق" يمنعه، والكود يُدرج بترتيب سطور السلة (`LinkedHashMap`) بلا فرز بالمعرّف. لم أتحقق إن كان ترتيب السلة يُفرض في مكان آخر أو إن كان الخطر قائمًا فعلًا.
6. **مفتاح Idempotency العالق `IN_PROGRESS` بعد انهيار العملية** بين إتمام معاملة الطلب وتسجيل النتيجة: استنتاج من تسلسل الاستدعاءات (2.6)، لم أجد مسارًا يعالج الحالة ولم أحاكها.
7. **ما يظهر في ملفات Excel/PDF/قالب البريد:** لم أقرأ `AccountingExcelWriter` ولا القوالب كاملةً (قرأتُ `InvoiceService.buildView` ووجود علامة "فاتورة ملغاة" في `invoice.html` فقط)؛ لا أعرف الأعمدة الدقيقة ولا نص البريد.
8. **منطق `CategoryService` و`FacetService` و`CategoryDetailResponse`:** لم أقرأ تفاصيل بناء الشجرة والـ facets (حدود العمق، ما يُحتسب في `productCount`، التعامل مع الفئات غير الفعّالة)، وما ورد عنها في 4.1 هو من التعليقات والـ DTO.
9. **سكربتات الترحيل V2–V18 كاملةً:** اعتمدتُ على `baseline_through_V14.sql` وREADME وقرأتُ فقط مقاطع من V5 (فهرس التسوية). لم أتحقق من أي backfill أو قيود أضافتها V12/V15/V16/V17/V18 إلى الجداول، ولا أن المخطط الحي يطابق الـ baseline.
10. **`CustomerAdminService` و`CustomerQueries`:** وصف الفرز والإحصاءات (`spent_desc`...) مأخوذ من توثيق الـ controller وسطر استثناء الموظفين؛ لم أقرأ الاستعلامات كاملة.
11. **تأثير `accept-legacy-unsigned: true` على كل مسارات الضيف:** قرأتُ `GuestTokenService.verify` (يقبل الرمز بلا نقطة)، لكن لم أتتبع كل المواضع التي تستعمله لأتأكد أنه لا توجد مسارات تقارن الرمز بقيمة مخزَّنة حرفيًا.
12. **سلوك السلة عند تعارض كميات متزامن (race)** بين `addItem` والـ checkout: الحماية الفعلية عند الحجز الذري فقط؛ لم أبحث عن سباقات أخرى (مثل إضافة متزامنة لنفس المتغيّر بقيد `uq_cart_variant`).
13. **تعليقات `@Operation` غير المطابقة للكود:** وجدتُ حالات (رسالة "A reason is required" في إلغاء الطلب، "Existing invoices keep the details" في ملف المتجر) تخالف الكود، لذلك لم أعتمد على أي `@Operation` وحده إلا حين أشرتُ لذلك صراحةً. قد توجد حالات أخرى لم أكتشفها.
14. **توافق الواجهة الأمامية:** الوثيقتان `docs/VELORA_API_Contract.md` و`docs/ROYAL_CHANGES.md` لم أعتمد عليهما إطلاقًا (طُلب التوثيق من الكود)، فقد تختلف عنه.
15. **المنطقة الزمنية لخادم الإنتاج:** تؤثر على سنة الفاتورة وتاريخ مرجع التسوية وحدود "اليوم/الشهر" في لوحة التحكم؛ لا أعرف إعدادها الفعلي.
16. **عدد الأدمن وكيفية منح الدور:** لا مسار في الكود يمنح `ADMIN`؛ افترضتُ إدخالًا يدويًا في `user_role` دون أن أجد سكربتًا يفعل ذلك.
17. **تزامن إصدار فاتورة بديلة من مستخدمَين:** الفهرس المرشَّح `uq_invoice_order` يرفض الثانية فتتراجع معاملتها ويرجع الرقم، هذا مبني على قراءة الكود وسلوك SQL Server؛ لم أختبره بتنفيذ متزامن فعلي (الاختبارات تتحقق من أن الفهرس يرفض صفًّا حيًّا ثانيًا ومن تسلسل الأرقام في مسار متتابع).
18. **أثر `ARITHABORT = OFF` على الاتصال في بيئات أخرى:** أثبتُّ على `royal_test` (توافق 170، سائق JDBC 13.4) أن الإدراج في جدول بفهرس مرشَّح ينجح، لكن لم أفحص نسخة SQL Server أو إعدادات اتصال بيئة الإنتاج.

المصدر: `src/main/java/com/velora/api/common/security/SecurityConfig.java:85-128` · `src/main/resources/db/README.md:17` · `src/main/resources/application.yml:64-72` · `src/main/java/com/velora/api/inventory/service/ReservationService.java:68-112` · `src/main/java/com/velora/api/idempotency/service/IdempotencyService.java:61-88` · `src/main/java/com/velora/api/cart/web/CartController.java:146-152` · `src/main/java/com/velora/api/cart/security/GuestTokenService.java:81-110`

