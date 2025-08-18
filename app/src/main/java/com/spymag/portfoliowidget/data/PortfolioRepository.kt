package com.spymag.portfoliowidget.data

data class PortfolioSummary(
    val bitvavoTotal: String,
    val trading212Total: String
)

class PortfolioRepository {

    private val bitvavoClient = BitvavoClient(HttpClient.instance)
    private val trading212Client = Trading212Client(HttpClient.instance)

    suspend fun getPortfolioSummary(): PortfolioSummary {
        val bitvavoValue = try {
            bitvavoClient.fetchTotalPortfolioValue()
        } catch (e: Exception) {
            // In a real app, you might want to log this with a proper logging framework
            e.printStackTrace()
            "–"
        }

        val trading212Value = try {
            trading212Client.fetchTrading212TotalValue()
        } catch (e: Exception) {
            e.printStackTrace()
            "–"
        }

        return PortfolioSummary(bitvavoTotal = bitvavoValue, trading212Total = trading212Value)
    }
}
