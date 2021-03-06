package payment

import grails.transaction.Transactional
import invoice.Invoice
import invoice.InvoiceCalculation
import invoice.InvoiceStatus
import party.Organization
import party.Party
import application.commandobject.RecordPaymentCommand
import core.Status
import core.StatusType

@Transactional
class PaymentService {
	
	def invoiceService
	
	def getPaymentsForParty(Party p) {
		def c = Payment.createCriteria()
		def payments = c.list {
			eq("partyFrom",p)
		}
		return payments
	}
	
	def getPaymentsForInvoice(Invoice invoice) {
		def c = Payment.createCriteria()
		def payments = c.list {
			paymentApplications {
				eq("invoice",invoice)
			}
		}
		return payments
	}

	def recordPaymentForInvoice(RecordPaymentCommand cmd) {
		if (cmd == null) {
			return null
		}
		
		def paymentInstance = new Payment()
		paymentInstance.paymentType = PaymentType.findByCode("CUSTOMER_PAYMENT")
		
		paymentInstance.partyFrom = Party.get(cmd.partyFromId)
		paymentInstance.partyTo = Organization.findByName("-COMPANY-")
		paymentInstance.effectiveDate = cmd.effectiveDate
		paymentInstance.comments = cmd.comments
		paymentInstance.amount = cmd.amount
		
		//Create payment application
		def invoice = Invoice.get(cmd.invoiceId)
		
		def paymentApplication = new PaymentApplication()
		paymentApplication.invoice = invoice
		paymentApplication.amountApplied = cmd.amount
		paymentApplication.payment = paymentInstance
		
		paymentInstance.addToPaymentApplications(paymentApplication)
		
		//Payment Status
		def paymentStatusType = StatusType.findByDescription("PAYMENT_STATUS")
		
		def paymentStatus = new PaymentStatus()
		paymentStatus.payment = paymentInstance
		paymentStatus.status = Status.findByStatusCodeAndStatusType("RECEIVED",paymentStatusType)
		paymentInstance.addToStatuses(paymentStatus)
		paymentInstance.currentStatus = paymentStatus
		
		if(paymentInstance.save(flush:true)) {
			cmd.id = paymentInstance.id
		}
		
		//Create payment method
		def paymentMethod = new PaymentMethod()
		paymentMethod.payment = paymentInstance
		paymentMethod.paymentMethodType = PaymentMethodType.findByCode(cmd.paymentMethodType)
		paymentInstance.paymentMethod = paymentMethod
		paymentMethod.save(flush:true)
		
		//Invoice Status.
		def unpaidAmount = invoiceService.getUnpaidAmountForInvoice(invoice)
		if(unpaidAmount > 0) {
			def status = Status.findByStatusCodeAndStatusType("PARTIALLY_PAID",
				StatusType.findByDescription("INVOICE_STATUS"))
			
			def invoiceStatus = new InvoiceStatus()
			invoiceStatus.status = status
			invoiceStatus.invoice = invoice
			invoiceStatus.save(flush:true)
			invoice.currentInvoiceStatus = invoiceStatus
			invoice.addToStatuses(invoiceStatus)
			invoice.save(flush:true)
		} else if(unpaidAmount <= 0) {
			def status = Status.findByStatusCodeAndStatusType("PAID",
				StatusType.findByDescription("INVOICE_STATUS"))
			
			def invoiceStatus = new InvoiceStatus()
			invoiceStatus.status = status
			invoiceStatus.invoice = invoice
			invoiceStatus.save(flush:true)
			invoice.currentInvoiceStatus = invoiceStatus
			invoice.addToStatuses(invoiceStatus)
			invoice.paidDate = cmd.effectiveDate
			invoice.save(flush:true)
			
			//TODO
			//If -ve unpaid balance adjust the credit.
		}
		
		//Add an invoice calculation
		def invoiceCalculation = new InvoiceCalculation()
		invoiceCalculation.calculationDate = new Date()
		invoiceCalculation.invoiceGrandTotal = invoiceService.getInvoiceTotalAmount(invoice)
		invoiceCalculation.currentReceivedAmount = invoiceService.getPaidAmountForInvoice(invoice)
		invoiceCalculation.currentReceivableAmount = invoiceService.getUnpaidAmountForInvoice(invoice)
		invoiceCalculation.invoice = invoice
		invoiceCalculation.save(flush:true)
		
		invoice.currentInvoiceCalculation = invoice
		invoice.save(flush:true)
		
		return paymentInstance
	}
}
