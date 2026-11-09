/* =====================================================================
   ROYAL - V29: make the imported products buyable, and give them stock

   DATA FIX, not a schema change. The bulk import used to save every product it created as MADE_TO_ORDER
   with no shipping size, so the storefront hid the price and the Add-to-cart button on each of them (the
   price still showed in search and on the cards). The importer is fixed to work this out itself
   (ImportFulfillmentRules); this script repairs the products it had already created.

   What it does, only for the products listed below (SKU -> shipping size, derived with the importer's own
   rule from the dimensions in the product's name / its dimension specifications: longest side under
   90 cm SMALL, up to 160 cm MEDIUM, above that LARGE):
     1. product.fulfillment_type  MADE_TO_ORDER -> READY_MADE, product.shipping_size_class set.
        Only a product that is still MADE_TO_ORDER with no size and is not archived is touched, so a product
        someone has since set by hand is left alone.
     2. Opening stock of 50 (and a PURCHASE_RECEIVED movement saying so) for a variant that has never had
        any stock at all: qty_on_hand 0, nothing reserved, no stock movement ever. Nothing else is changed.
     3. min_stock_level 3 (the old default) -> 10 on those variants: the low-stock alert level.
   Nothing here touches a price, an order, an invoice, an image or a product's status.

   Re-runnable: a second run finds nothing left to change. One transaction.
   Apply with:  sqlcmd -d royal -f 65001 -b -i V29__fix_imported_fulfillment.sql
   Check the sizes first - the shipping size decides what the customer pays for delivery.
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

DECLARE @fix TABLE (sku VARCHAR(60) NOT NULL PRIMARY KEY, size_class VARCHAR(10) NOT NULL);

INSERT INTO @fix (sku, size_class) VALUES
    ('CH101', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 44×44×90سم CH101
  , ('CH102', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود ورصاصي 40×40×90سم CH102
  , ('CH103', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود ورصاصي 44×44×90سم CH103
  , ('CH104', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أبيض 55×60×90سم CH104
  , ('CH105', 'MEDIUM')   -- كرسي بار حديد بقعدة خشب - بني وأسود 35×35×90سم CH105
  , ('CH106', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود 40×40×70سم CH106
  , ('CH107', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 40×40×90سم CH107
  , ('CH108', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - أسود 40×40×70سم CH108
  , ('CH109', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 45×45×70سم CH109
  , ('CH110', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - أسود وبني 50×40×50سم CH110
  , ('CH111', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبيج 40×50×100سم CH111
  , ('CH112', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - ذهبي وأبيض 45×45×70سم CH112
  , ('CH113', 'SMALL')   -- بوف حديد بتنجيد قماش - أسود 45×45×50سم CH113
  , ('CH114', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وبيج 45×45×90سم CH114
  , ('CH115', 'SMALL')   -- بوف حديد بتنجيد قماش - أسود 50×50×50سم CH115
  , ('CH116', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 35×35×70سم CH116
  , ('CH117', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود ورصاصي 40×40×70سم CH117
  , ('CH118', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - ذهبي وزيتي 40×40×70سم CH118
  , ('CH119', 'SMALL')   -- بوف حديد بتنجيد قماش - أسود 45×45×50سم CH119
  , ('CH120', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 44×40×90سم CH120
  , ('CH121', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود ورصاصي 45×50×105سم CH121
  , ('CH122', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - أسود وأبيض 40×40×70سم CH122
  , ('CH123', 'SMALL')   -- بوف حديد بتنجيد قماش - ذهبي وكشمير 50×50×50سم CH123
  , ('CH124', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 45×50×110سم CH124
  , ('CH125', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 35×40×65سم CH125
  , ('CH126', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - أسود ورصاصي 40×40×70سم CH126
  , ('CH127', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود 40×40×90سم CH127
  , ('CH128', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 40×40×65سم CH128
  , ('CH129', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود 44×44×90سم CH129
  , ('CH130', 'SMALL')   -- كرسي فوتيه حديد بتنجيد قماش - ذهبي ورصاصي 70×80×80سم CH130
  , ('CH131', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - ذهبي وأسود 40×40×70سم CH131
  , ('CH132', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 35×35×70سم CH132
  , ('CH133', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 50×55×90سم CH133
  , ('CH134', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 35×35×70سم CH134
  , ('CH135', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أبيض 50×50×110سم CH135
  , ('CH136', 'SMALL')   -- كرسي فوتيه حديد بتنجيد جلد - أسود 60×65×80سم CH136
  , ('CH137', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - أسود وبني 40×40×70سم CH137
  , ('CH138', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 40×40×70سم CH138
  , ('CH139', 'SMALL')   -- كرسي فوتيه حديد بتنجيد قماش - أسود ورصاصي 70×80×80سم CH139
  , ('CH140', 'SMALL')   -- بوف حديد بتنجيد قماش - أسود ورصاصي 50×50×50سم CH140
  , ('CH141', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - ذهبي ورصاصي 45×45×70سم CH141
  , ('CH142', 'SMALL')   -- كرسي سفرة حديد بتنجيد جلد - ذهبي وأبيض 45×45×70سم CH142
  , ('CH143', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 40×40×90سم CH143
  , ('CH144', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود 50×60×80سم CH144
  , ('CH145', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود وزيتي 44×44×90سم CH145
  , ('CH146', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود 40×40×70سم CH146
  , ('CH147', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود وأبيض 50×50×70سم CH147
  , ('CH148', 'SMALL')   -- بوف حديد بتنجيد قماش - ذهبي وكشمير 50×50×50سم CH148
  , ('CH149', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 44×44×90سم CH149
  , ('CH150', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أبيض ورصاصي 44×44×90سم CH150
  , ('CH151', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 44×44×90سم CH151
  , ('CH152', 'SMALL')   -- كرسي سفرة حديد بتنجيد قماش - أسود وأبيض 40×40×70سم CH152
  , ('CH153', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود 50×50×110سم CH153
  , ('CH154', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود وأبيض 45×45×110سم CH154
  , ('CH155', 'SMALL')   -- كرسي سفرة حديد بقعدة خشب - أسود 40×40×70سم CH155
  , ('CH156', 'SMALL')   -- بوف حديد بتنجيد جلد - ذهبي 50×50×50سم CH156
  , ('CH157', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي 45×55×110سم CH157
  , ('CH158', 'SMALL')   -- بوف حديد بتنجيد قماش - ذهبي ورصاصي 50×50×50سم CH158
  , ('CH159', 'MEDIUM')   -- كرسي بار حديد بتنجيد كتان - ذهبي وأزرق 60×55×100سم CH159
  , ('CH160', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وهافان 60×55×100سم CH160
  , ('CH161', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وزيتي 60×55×100سم CH161
  , ('CH162', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - فضي وبترولي 60×55×100سم CH162
  , ('CH163', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وبترولي 60×55×100سم CH163
  , ('CH164', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأسود 60×55×100سم CH164
  , ('CH165', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 60×55×100سم CH165
  , ('CH166', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وبيج 60×55×100سم CH166
  , ('CH167', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي ولبني 60×55×100سم CH167
  , ('CH168', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - فضي وأسود 60×55×100سم CH168
  , ('CH169', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود ورصاصي 60×55×100سم CH169
  , ('CH170', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - فضي وبيج 60×55×100سم CH170
  , ('CH171', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبيج 60×55×100سم CH171
  , ('CH172', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وزيتي 60×55×100سم CH172
  , ('CH173', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 60×55×100سم CH173
  , ('CH174', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبترولي 60×55×100سم CH174
  , ('CH175', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأسود 60×55×100سم CH175
  , ('CH176', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 60×55×100سم CH176
  , ('CH177', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي ورصاصي 45×45×95سم CH177
  , ('CH178', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 45×40×70سم CH178
  , ('CH179', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي ونحاسي 45×45×95سم CH179
  , ('CH180', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وزيتي 40×50×110سم CH180
  , ('CH181', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأسود 40×50×110سم CH181
  , ('CH182', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 40×40×90سم CH182
  , ('CH183', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أبيض وهافان 40×40×90سم CH183
  , ('CH184', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أبيض وبني 40×40×90سم CH184
  , ('CH185', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 40×40×70سم CH185
  , ('CH186', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأسود 50×50×110سم CH186
  , ('CH187', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي ورصاصي 50×50×110سم CH187
  , ('CH188', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 50×50×110سم CH188
  , ('CH189', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود ورصاصي 40×50×95سم CH189
  , ('CH190', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 50×50×110سم CH190
  , ('CH191', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود 45×50×110سم CH191
  , ('CH192', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي ورصاصي 45×45×95سم CH192
  , ('CH193', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 40×40×90سم CH193
  , ('CH194', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - أسود 40×40×90سم CH194
  , ('CH195', 'MEDIUM')   -- كرسي بار حديد بتنجيد قماش - ذهبي وبيج 55×50×95سم CH195
  , ('CH196', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - أسود ورصاصي 40×35×70سم CH196
  , ('CH197', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وبيج 55×50×95سم CH197
  , ('CH198', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 40×40×90سم CH198
  , ('CH199', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 40×40×90سم CH199
  , ('CH200', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - أسود 45×40×70سم CH200
  , ('CH202', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 40×35×70سم CH202
  , ('CH203', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - ذهبي ورصاصي 40×35×70سم CH203
  , ('CH204', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 40×35×70سم CH204
  , ('CH205', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - ذهبي وأبيض 40×40×90سم CH205
  , ('CH206', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وبترولي 55×50×95سم CH206
  , ('CH207', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 50×50×90سم CH207
  , ('CH208', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي ورصاصي 40×50×110سم CH208
  , ('CH209', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وكشمير 40×50×110سم CH209
  , ('CH210', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 40×40×90سم CH210
  , ('CH211', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وكشمير 55×50×100سم CH211
  , ('CH212', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأوف وايت 55×50×100سم CH212
  , ('CH213', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وكشمير 55×50×100سم CH213
  , ('CH214', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 55×50×100سم CH214
  , ('CH215', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي ورصاصي 55×50×100سم CH215
  , ('CH216', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 55×50×100سم CH216
  , ('CH217', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وزيتي 55×50×100سم CH217
  , ('CH218', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبني 55×50×100سم CH218
  , ('CH219', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي ورصاصي 55×50×100سم CH219
  , ('CH220', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأوف وايت 55×50×100سم CH220
  , ('CH221', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وأوف وايت 50×45×90سم CH221
  , ('CH222', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 40×40×90سم CH222
  , ('CH223', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي ورصاصي 40×50×110سم CH223
  , ('CH224', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 45×50×110سم CH224
  , ('CH225', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وزيتي 50×50×110سم CH225
  , ('CH226', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبني 40×50×110سم CH226
  , ('CH227', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأسود 45×45×95سم CH227
  , ('CH228', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وأوف وايت 55×50×95سم CH228
  , ('CH229', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 50×45×90سم CH229
  , ('CH230', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وأبيض 55×50×95سم CH230
  , ('CH231', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وزيتي 45×45×110سم CH231
  , ('CH232', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - أسود وأوف وايت 40×40×70سم CH232
  , ('CH233', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 40×50×95سم CH233
  , ('CH234', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وبيج 55×50×100سم CH234
  , ('CH235', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وبني 40×40×90سم CH235
  , ('CH236', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 55×55×90سم CH236
  , ('CH237', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبيج 45×45×90سم CH237
  , ('CH238', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وزيتي 45×50×110سم CH238
  , ('CH239', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبيج 45×50×110سم CH239
  , ('CH240', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبيج 45×50×110سم CH240
  , ('CH241', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود ورصاصي 45×50×110سم CH241
  , ('CH242', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وأبيض 55×50×95سم CH242
  , ('CH243', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - بني وبيج 45×50×110سم CH243
  , ('CH244', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبني 40×50×110سم CH244
  , ('CH245', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود ورصاصي 45×50×110سم CH245
  , ('CH246', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود 55×55×90سم CH246
  , ('CH247', 'SMALL')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 45×45×70سم CH247
  , ('CH248', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وزيتي 40×50×110سم CH248
  , ('CH249', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - ذهبي وأسود 40×50×95سم CH249
  , ('CH250', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 44×40×90سم CH250
  , ('CH251', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود وأبيض 44×40×90سم CH251
  , ('CH252', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود وبيج 44×40×90سم CH252
  , ('CH253', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 44×40×90سم CH253
  , ('CH254', 'MEDIUM')   -- كرسي بار حديد بتنجيد قطيفة - أسود ورصاصي 44×40×90سم CH254
  , ('CHH201', 'MEDIUM')   -- كرسي بار حديد بتنجيد جلد - أسود 50×50×110سم CHH201
;

DECLARE @products_fixed INT, @stock_given INT, @alert_changed INT;

-- 1. ready-made, with a shipping size
UPDATE p
   SET p.fulfillment_type = 'READY_MADE',
       p.shipping_size_class = f.size_class,
       p.updated_at = SYSDATETIMEOFFSET()
  FROM dbo.product p
  JOIN dbo.product_variant v ON v.product_id = p.id
  JOIN @fix f ON f.sku = v.sku
 WHERE p.fulfillment_type = 'MADE_TO_ORDER'
   AND p.shipping_size_class IS NULL
   AND p.status <> 'ARCHIVED';
SET @products_fixed = @@ROWCOUNT;

-- 2. opening stock for a variant that never had any, written to the ledger as well
DECLARE @given TABLE (variant_id BIGINT NOT NULL, qty_after INT NOT NULL);

UPDATE i
   SET i.qty_on_hand = 50,
       i.version = i.version + 1,
       i.updated_at = SYSDATETIMEOFFSET()
OUTPUT inserted.variant_id, inserted.qty_on_hand INTO @given (variant_id, qty_after)
  FROM dbo.inventory i
  JOIN dbo.product_variant v ON v.id = i.variant_id
  JOIN @fix f ON f.sku = v.sku
  JOIN dbo.product p ON p.id = v.product_id
 WHERE p.fulfillment_type = 'READY_MADE'
   AND i.qty_on_hand = 0
   AND i.qty_reserved = 0
   AND NOT EXISTS (SELECT 1 FROM dbo.stock_movement m WHERE m.variant_id = i.variant_id);
SET @stock_given = @@ROWCOUNT;

INSERT INTO dbo.stock_movement
    (variant_id, movement_type, quantity_delta, qty_after, reference_type, reference_id, reason, actor_id, actor_name)
SELECT g.variant_id, 'PURCHASE_RECEIVED', g.qty_after, g.qty_after, 'DEFAULT_STOCK', CAST(g.variant_id AS VARCHAR(60)),
       N'مخزون افتراضي للمنتجات المستوردة', NULL, N'إصلاح بيانات V29'
  FROM @given g;

-- 3. the low-stock alert level
UPDATE i
   SET i.min_stock_level = 10,
       i.version = i.version + 1,
       i.updated_at = SYSDATETIMEOFFSET()
  FROM dbo.inventory i
  JOIN dbo.product_variant v ON v.id = i.variant_id
  JOIN @fix f ON f.sku = v.sku
  JOIN dbo.product p ON p.id = v.product_id
 WHERE p.fulfillment_type = 'READY_MADE'
   AND i.min_stock_level = 3;
SET @alert_changed = @@ROWCOUNT;

SELECT @products_fixed AS products_made_ready, @stock_given AS variants_given_stock, @alert_changed AS alert_levels_set;

COMMIT TRANSACTION;
GO