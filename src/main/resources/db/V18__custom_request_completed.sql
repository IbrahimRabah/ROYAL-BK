/* =====================================================================
   VELORA - V18: custom_order_request gains the COMPLETED status

   COMPLETED is terminal: the accepted work is finished and the file is closed. It is NOT
   CONVERTED - no order is created. Only ACCEPTED can reach it (enforced in the service).
   Widens ck_custom_req_status; no data changes.

   Safe to run again. Apply with:  sqlcmd -d <database> -f 65001 -b -i V18__custom_request_completed.sql
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_custom_req_status'
             AND parent_object_id = OBJECT_ID('dbo.custom_order_request')
             AND definition NOT LIKE '%COMPLETED%')
BEGIN
    ALTER TABLE dbo.custom_order_request DROP CONSTRAINT ck_custom_req_status;
    ALTER TABLE dbo.custom_order_request WITH CHECK ADD CONSTRAINT ck_custom_req_status
        CHECK (status IN ('NEW', 'CONTACTED', 'QUOTED', 'ACCEPTED', 'REJECTED', 'CONVERTED', 'COMPLETED'));
END
GO
