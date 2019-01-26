package com.lightning.walletapp

import android.view._
import android.widget._
import com.lightning.walletapp.ln._
import android.text.format.DateUtils._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.Channel._
import com.lightning.walletapp.Denomination._
import com.github.kevinsawicki.http.HttpRequest._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import com.lightning.walletapp.lnutils.ImplicitConversions._

import android.app.{Activity, AlertDialog}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import com.lightning.walletapp.lnutils.{GDrive, PaymentInfoWrap}
import com.lightning.walletapp.lnutils.JsonHttpUtils.{obsOnIO, to}
import com.lightning.walletapp.lnutils.IconGetter.{bigFont, scrWidth}
import com.lightning.walletapp.ln.RoutingInfoTag.PaymentRoute
import com.lightning.walletapp.ln.wire.NodeAnnouncement
import android.support.v4.app.FragmentStatePagerAdapter
import org.ndeftools.util.activity.NfcReaderActivity
import com.lightning.walletapp.helper.AwaitService
import android.support.v4.content.ContextCompat
import com.github.clans.fab.FloatingActionMenu
import android.support.v7.widget.SearchView
import fr.acinq.bitcoin.Crypto.PublicKey
import android.text.format.DateFormat
import org.bitcoinj.uri.BitcoinURI
import java.text.SimpleDateFormat
import org.bitcoinj.core.TxWrap
import android.content.Intent
import org.ndeftools.Message
import android.os.Bundle
import java.util.Date


trait SearchBar { me =>
  var isSearching = false
  var lastQuery = new String
  var searchView: SearchView = _

  def setupSearch(m: Menu) = {
    searchView = m.findItem(R.id.action_search).getActionView.asInstanceOf[SearchView]
    searchView addOnAttachStateChangeListener new View.OnAttachStateChangeListener {
      def onViewDetachedFromWindow(lens: View) = runAnd(isSearching = false)(react)
      def onViewAttachedToWindow(lens: View) = runAnd(isSearching = true)(react)
    }

    searchView setOnQueryTextListener new SearchView.OnQueryTextListener {
      def onQueryTextChange(txt: String) = runAnd(true)(me search txt)
      def onQueryTextSubmit(txt: String) = true
    }
  }

  def react: Unit
  def search(txt: String) = {
    // Update and do the search
    lastQuery = txt
    react
  }
}

trait HumanTimeDisplay {
  val host: TimerActivity
  // Should be accessed after activity is initialized
  lazy val timeString = DateFormat is24HourFormat host match {
    case false if scrWidth < 2.2 & bigFont => "MM/dd/yy' <small>'h:mma'</small>'"
    case false if scrWidth < 2.2 => "MM/dd/yy' <small>'h:mma'</small>'"

    case false if scrWidth < 2.5 & bigFont => "MM/dd/yy' <small>'h:mma'</small>'"
    case false if scrWidth < 2.5 => "MM/dd/yy' <small>'h:mma'</small>'"
    case false => "MMM dd, yyyy' <small>'h:mma'</small>'"

    case true if scrWidth < 2.2 & bigFont => "d MMM yyyy' <small>'HH:mm'</small>'"
    case true if scrWidth < 2.2 => "d MMM yyyy' <small>'HH:mm'</small>'"

    case true if scrWidth < 2.4 & bigFont => "d MMM yyyy' <small>'HH:mm'</small>'"
    case true if scrWidth < 2.5 => "d MMM yyyy' <small>'HH:mm'</small>'"
    case true => "d MMM yyyy' <small>'HH:mm'</small>'"
  }

  val time: Date => String = new SimpleDateFormat(timeString) format _
  def when(now: Long, thenDate: Date) = thenDate.getTime match { case ago =>
    if (now - ago < 129600000) getRelativeTimeSpanString(ago, now, 0).toString
    else time(thenDate)
  }

  def initToolbar(toolbar: android.support.v7.widget.Toolbar) = {
    // Show back arrow button to allow users to get back to wallet
    // just kill current activity once a back button is tapped

    host.setSupportActionBar(toolbar)
    host.getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    host.getSupportActionBar.setDisplayShowHomeEnabled(true)
    toolbar.setNavigationOnClickListener(host onButtonTap host.finish)
  }
}

class WalletActivity extends NfcReaderActivity with ScanActivity { me =>
  lazy val awaitServiceIntent: Intent = new Intent(me, AwaitService.classof)
  lazy val floatingActionMenu = findViewById(R.id.fam).asInstanceOf[FloatingActionMenu]
  lazy val slidingFragmentAdapter = new FragmentStatePagerAdapter(getSupportFragmentManager) {
    def getItem(currentFragmentPos: Int) = if (0 == currentFragmentPos) new FragWallet else new FragScan
    def getCount = 2
  }

  override def onDestroy = wrap(super.onDestroy)(stopDetecting)
  override def onResume = wrap(super.onResume)(me returnToBase null)
  override def onOptionsItemSelected(m: MenuItem): Boolean = runAnd(true) {
    if (m.getItemId == R.id.actionSettings) me goTo classOf[SettingsActivity]
  }

  override def onBackPressed = {
    val isExpanded = FragWallet.worker.currentCut > FragWallet.worker.minLinesNum
    if (1 == walletPager.getCurrentItem) walletPager.setCurrentItem(0, true)
    else if (floatingActionMenu.isOpened) floatingActionMenu close true
    else if (isExpanded) FragWallet.worker.toggler.performClick
    else super.onBackPressed
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    // Called after fragLN sets toolbar as actionbar
    getMenuInflater.inflate(R.menu.wallet, menu)
    // Updated here to make sure it's present
    FragWallet.worker setupSearch menu
    true
  }

  def INIT(state: Bundle) = if (app.isAlive) {
    wrap(me setDetecting true)(me initNfc state)
    me setContentView R.layout.activity_double_pager
    walletPager setAdapter slidingFragmentAdapter

    val shouldCheck = app.prefs.getLong(AbstractKit.GDRIVE_LAST_SAVE, 0L) <= 0L // Unknown or failed
    val needsCheck = !GDrive.isMissing(app) && app.prefs.getBoolean(AbstractKit.GDRIVE_ENABLED, true) && shouldCheck
    if (needsCheck) obsOnIO.map(_ => GDrive signInAccount me).foreach(accountOpt => if (accountOpt.isEmpty) askGDriveSignIn)
  } else me exitTo classOf[MainActivity]

  override def onActivityResult(reqCode: Int, resultCode: Int, results: Intent) = {
    val isGDriveSignInSuccessful = reqCode == 102 && resultCode == Activity.RESULT_OK
    app.prefs.edit.putBoolean(AbstractKit.GDRIVE_ENABLED, isGDriveSignInSuccessful).commit
    if (!isGDriveSignInSuccessful) app toast gdrive_disabled
  }

  // NFC

  def readEmptyNdefMessage = app toast err_no_data
  def readNonNdefMessage = app toast err_no_data
  def onNfcStateChange(ok: Boolean) = none
  def onNfcFeatureNotFound = none
  def onNfcStateDisabled = none
  def onNfcStateEnabled = none

  def readNdefMessage(m: Message) =
    <(app.TransData recordValue ndefMessageString(m),
      _ => app toast err_no_data)(_ => checkTransData)

  // EXTERNAL DATA CHECK

  def checkTransData = app.TransData checkAndMaybeErase {
    // TransData value should be retained in both of these cases
    case _: NodeAnnouncement => me goTo classOf[LNStartFundActivity]

    case FragWallet.REDIRECT =>
      // TransData value should be erased here
      // so goOps return type is forced to Unit
      goOps(null): Unit

    case uri: BitcoinURI =>
      // TransData value will be erased here
      val manager = FragWallet.worker.sendBtcPopup(uri.getAddress)(none)
      // Prohibit sum editing if uri contains a definite amount
      manager maybeLockAmount uri
      me returnToBase null

    case lnUrl: LNUrl =>
      // Got an explicit lnurl, should resolve
      // TransData value will be erased here
      resolveUrl(None, lnUrl)
      me returnToBase null

    case pr: PaymentRequest =>
      val hasOpeningChans = ChannelManager.notClosingOrRefunding.exists(isOpening)
      val maxLocalSend = ChannelManager.all.filter(isOperational).map(estimateCanSend)

      if (!pr.isFresh) {
        // Expired payment request, reject
        // TransData value will be erased here
        app toast dialog_pr_expired
        me returnToBase null

      } else if (PaymentRequest.prefixes(LNParams.chainHash) != pr.prefix) {
        // Payee has provided a payment request from some other network, reject
        // TransData value will be erased here
        app toast err_general
        me returnToBase null

      } else if (hasOpeningChans && maxLocalSend.isEmpty) {
        // Only opening channels are present, tell use about it
        onFail(app getString err_ln_still_opening)
        // TransData value will be erased here
        me returnToBase null

      } else if (maxLocalSend.isEmpty) {
        // No channels are present at all currently, offer to open a new one
        if (pr.amount.exists(_ > app.kit.conf0Balance) || app.kit.conf0Balance.isZero) {
          // They have requested too much or there is no amount but on-chain wallet is empty
          // TransData value will be erased here
          onFail(app getString ln_send_howto)
          me returnToBase null

        } else {
          // TransData is set to batch or null to erase previous value
          app.TransData.value = TxWrap findBestBatch pr getOrElse null
          // Do not erase data which we have just updated
          app toast err_ln_no_open_chans
          goStart
        }

      } else if (pr.lnUrlOpt.isDefined) {
        // Should resolve an embedded lnurl
        resolveUrl(Some(pr), pr.lnUrlOpt.get)
        // TransData value will be erased here
        me returnToBase null

      } else {
        // We have operational channels, pass it further
        FragWallet.worker.sendPayment(maxLocalSend, pr)
        // TransData value will be erased here
        me returnToBase null
      }

    case _ =>
  }

  // LNURL

  def resolveUrl(prOpt: Option[PaymentRequest], lnUrl: LNUrl) =
    scala.util.Try(lnUrl.uri getQueryParameter "tag") -> prOpt match {
      case scala.util.Success("link") \ Some(pr) => showLinkSendForm(lnUrl, pr)
      case scala.util.Success("login") \ None => showLoginForm(lnUrl)
      case _ => resolveStandardUrl(lnUrl)
    }

  def resolveStandardUrl(lNUrl: LNUrl) = {
    val initialRequest = get(lNUrl.uri.toString, true).trustAllCerts.trustAllHosts
    val ask = obsOnIO.map(_ => initialRequest.connectTimeout(5000).body) map to[LNUrlData]
    ask.doOnSubscribe(app toast ln_url_resolving).foreach(doResolve, onFail)

    def doResolve(data: LNUrlData): Unit = data match {
      case inChanReq: IncomingChannelRequest => initConnection(inChanReq)
      case wthdReq: WithdrawRequest => doReceivePayment(wthdReq :: Nil)
      case unknown => throw new Exception(s"Unrecognized $unknown")
    }
  }

  def initConnection(incoming: IncomingChannelRequest) = {
    ConnectionManager.listeners += new ConnectionListener { self =>
      override def onOperational(nodeId: PublicKey, isCompat: Boolean) = if (isCompat) {
        // Remove listener and make a request, OpenChannel message should arrive shortly
        obsOnIO.map(_ => incoming.requestChannel).foreach(none, none)
        ConnectionManager.listeners -= self
      }
    }

    // Make sure we definitely have an LN connection before asking
    ConnectionManager.connectTo(incoming.getAnnounce, notify = true)
  }

  def showLoginForm(lnUrl: LNUrl) = {
    scala.util.Try(lnUrl.uri getQueryParameter "c").map(BinaryData.apply) match {
      case scala.util.Success(loginChallenge) => offerLogin(loginChallenge take 64)
      case _ => app toast err_no_data
    }

    def offerLogin(challenge: BinaryData) = {
      val title = updateView2Blue(str2View(new String), s"<big>${lnUrl.uri.getHost}</big>")
      lazy val linkingPrivKey = LNParams.getLinkingKey(domainName = lnUrl.uri.getHost)
      lazy val pub = linkingPrivKey.publicKey.toString

      mkCheckFormNeutral(doLogin, none, _ => {
        val msg = getString(ln_url_info_linking).format(lnUrl.uri.getHost, pub, lnUrl.uri.getHost).html
        mkCheckFormNeutral(_.dismiss, none, _ => me share pub, baseTextBuilder(msg), dialog_ok, -1, dialog_share_key)
      }, baseBuilder(title, null), dialog_login, dialog_cancel, dialog_wut)

      def doLogin(alert: AlertDialog) = rm(alert) {
        val signature = Crypto encodeSignature Crypto.sign(challenge, linkingPrivKey)
        val secondLevelCallback = get(s"${lnUrl.request}&key=$pub&sig=${signature.toString}", true)
        val secondLevelRequest = secondLevelCallback.connectTimeout(5000).trustAllCerts.trustAllHosts
        obsOnIO.map(_ => secondLevelRequest.body).map(LNUrlData.guardResponse).foreach(none, onFail)
        app toast ln_url_resolving
      }
    }
  }

  def showLinkSendForm(lnUrl: LNUrl, pr: PaymentRequest) = {

  }

  // BUTTONS REACTIONS

  def doReceivePayment(wrOpt: List[WithdrawRequest] = Nil) = {
    val openingOrOpenChannels: Vector[Channel] = ChannelManager.notClosingOrRefunding
    val operationalChannelsWithRoutes: Map[Channel, PaymentRoute] = openingOrOpenChannels.flatMap(channelAndHop).toMap
    val maxCanReceive = MilliSatoshi(operationalChannelsWithRoutes.keys.map(estimateCanReceiveCapped).reduceOption(_ max _) getOrElse 0L)
    val reserveUnspent = getString(ln_receive_reserve) format denom.coloredP2WSH(-maxCanReceive, denom.sign)

    wrOpt match {
      case wr :: Nil =>
        val title = updateView2Blue(str2View(new String), app getString ln_receive_title)
        val finalMaxCanReceive = if (wr.maxWithdrawable > maxCanReceive) maxCanReceive else wr.maxWithdrawable
        if (openingOrOpenChannels.isEmpty) showForm(negTextBuilder(dialog_ok, getString(ln_receive_howto).html).create)
        else if (operationalChannelsWithRoutes.isEmpty) showForm(negTextBuilder(dialog_ok, getString(ln_receive_6conf).html).create)
        else if (maxCanReceive.amount < 0L) showForm(alertDialog = negTextBuilder(neg = dialog_ok, msg = reserveUnspent.html).create)
        else FragWallet.worker.receive(operationalChannelsWithRoutes, finalMaxCanReceive, title, wr.defaultDescription) { rd =>
          def onRequestFailed(serverResponseFail: Throwable) = wrap(PaymentInfoWrap failOnUI rd)(me onFail serverResponseFail)
          obsOnIO.map(_ => wr requestWithdraw rd.pr).map(LNUrlData.guardResponse).foreach(none, onRequestFailed)
          PaymentInfoWrap.updStatus(PaymentInfo.WAITING, rd.pr.paymentHash)
          PaymentInfoWrap.uiNotify
        }

      case _ =>
        val alertLNHint =
          if (openingOrOpenChannels.isEmpty) getString(ln_receive_suggestion)
          else if (operationalChannelsWithRoutes.isEmpty) getString(ln_receive_6conf)
          else if (maxCanReceive.amount < 0L) reserveUnspent
          else getString(ln_receive_ok)

        val lst = getLayoutInflater.inflate(R.layout.frag_center_list, null).asInstanceOf[ListView]
        val alert = showForm(negBuilder(dialog_cancel, me getString action_coins_receive, lst).create)
        val options = Array(getString(ln_receive_option).format(alertLNHint).html, getString(btc_receive_option).html)

        def offChain = rm(alert) {
          if (openingOrOpenChannels.isEmpty) showForm(negTextBuilder(dialog_ok, app.getString(ln_receive_howto).html).create)
          else FragWallet.worker.receive(operationalChannelsWithRoutes, maxCanReceive, app.getString(ln_receive_title).html) { rd =>
            awaitServiceIntent.putExtra(AwaitService.SHOW_AMOUNT, denom asString rd.pr.amount.get).setAction(AwaitService.SHOW_AMOUNT)
            ContextCompat.startForegroundService(me, awaitServiceIntent)
            me goTo classOf[RequestActivity]
            app.TransData.value = rd.pr
          }
        }

        def onChain = rm(alert) {
          app.TransData.value = app.kit.currentAddress
          me goTo classOf[RequestActivity]
        }

        lst setOnItemClickListener onTap { case 0 => offChain case 1 => onChain }
        lst setAdapter new ArrayAdapter(me, R.layout.frag_top_tip, R.id.titleTip, options)
        lst setDividerHeight 0
        lst setDivider null
    }
  }

  def goSendPayment(top: View) = {
    val fragCenterList = getLayoutInflater.inflate(R.layout.frag_center_list, null).asInstanceOf[ListView]
    val alert = showForm(negBuilder(dialog_cancel, me getString action_coins_send, fragCenterList).create)
    val options = Array(send_scan_qr, send_paste_payment_request, send_hivemind_deposit).map(res => getString(res).html)
    fragCenterList setOnItemClickListener onTap { case 0 => scanQR case 1 => pasteRequest case 2 => depositHivemind }
    fragCenterList setAdapter new ArrayAdapter(me, R.layout.frag_top_tip, R.id.titleTip, options)
    fragCenterList setDividerHeight 0
    fragCenterList setDivider null

    def scanQR = rm(alert) {
      // Just jump to QR scanner section
      walletPager.setCurrentItem(1, true)
    }

    def pasteRequest = rm(alert) {
      val resultTry = app.getBufferTry map app.TransData.recordValue
      if (resultTry.isSuccess) checkTransData else app toast err_no_data
    }

    def depositHivemind = rm(alert) {
      // Show a warning for now since hivemind sidechain is not enabled yet
      val alert = showForm(negTextBuilder(dialog_ok, getString(hivemind_details).html).create)
      try Utils clickableTextField alert.findViewById(android.R.id.message) catch none
    }
  }

  val tokensPrice = MilliSatoshi(1000000L)
  def goStart = me goTo classOf[LNStartActivity]
  def goOps(top: View) = me goTo classOf[LNOpsActivity]
  def goReceivePayment(top: View) = doReceivePayment(Nil)
  def goAddChannel(top: View) = if (app.olympus.backupExhausted) {
    val withFiatAmount = denom.coloredIn(tokensPrice, denom.sign) + s"<font color=#999999>${msatInFiatHuman apply tokensPrice}</font>"
    val bld = baseTextBuilder(getString(tokens_warn).format(withFiatAmount).html).setCustomTitle(me getString action_ln_open)
    mkCheckForm(alert => rm(alert)(goStart), none, bld, dialog_ok, dialog_cancel)
  } else goStart
}