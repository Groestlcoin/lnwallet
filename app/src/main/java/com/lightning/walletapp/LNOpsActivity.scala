package com.lightning.walletapp

import spray.json._
import android.widget._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.ln.Channel._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._

import org.bitcoinj.core.{Block, FilteredBlock, Peer}
import android.view.{Menu, MenuItem, View, ViewGroup}
import com.lightning.walletapp.ln.Tools.{none, runAnd, wrap}
import com.lightning.walletapp.ln.{Channel, ChannelData, RefundingData}
import com.lightning.walletapp.ln.RoutingInfoTag.PaymentRoute
import com.lightning.walletapp.lnutils.IconGetter.scrWidth
import com.lightning.walletapp.lnutils.PaymentTable
import com.lightning.walletapp.helper.RichCursor
import android.support.v7.widget.Toolbar
import co.infinum.goldfinger.Goldfinger
import fr.acinq.bitcoin.Satoshi
import android.content.Intent
import scodec.bits.ByteVector
import android.os.Bundle
import android.net.Uri
import java.util.Date


class LNOpsActivity extends TimerActivity with HumanTimeDisplay { me =>
  lazy val displayedChans = for (channel <- ChannelManager.all if me canDisplay channel.data) yield channel
  lazy val chanActions = for (txt <- getResources getStringArray R.array.ln_chan_actions_list) yield txt.html
  lazy val presentChans = app.getResources getStringArray R.array.ln_chan_present
  lazy val gridView = findViewById(R.id.gridView).asInstanceOf[GridView]
  lazy val host = me

  val adapter = new BaseAdapter {
    def getItem(position: Int) = displayedChans(position)
    def getItemId(chanPosition: Int) = chanPosition
    def getCount = displayedChans.size

    def getView(position: Int, savedView: View, parent: ViewGroup) = {
      val card = if (null == savedView) getLayoutInflater.inflate(R.layout.chan_card, null) else savedView
      val holder = if (null == card.getTag) new ViewHolder(card) else card.getTag.asInstanceOf[ViewHolder]
      holder fillView getItem(position)
      card
    }
  }

  val eventsListener = new ChannelListener with BlocksListener {
    override def onBecome: PartialFunction[Transition, Unit] = { case anyStateChange => UITask(adapter.notifyDataSetChanged).run }
    def onBlocksDownloaded(p: Peer, b: Block, fb: FilteredBlock, left: Int) = if (left < 1) UITask(adapter.notifyDataSetChanged).run
  }

  class ViewHolder(view: View) {
    val extraInfo = view.findViewById(R.id.extraInfo).asInstanceOf[View]
    val baseBar = view.findViewById(R.id.baseBar).asInstanceOf[ProgressBar]
    val overBar = view.findViewById(R.id.overBar).asInstanceOf[ProgressBar]
    val extraInfoText = view.findViewById(R.id.extraInfoText).asInstanceOf[TextView]
    val addressAndKey = view.findViewById(R.id.addressAndKey).asInstanceOf[TextView]
    val stateAndConnectivity = view.findViewById(R.id.stateAndConnectivity).asInstanceOf[TextView]
    def setExtraInfo(text: CharSequence) = wrap(extraInfo setVisibility View.VISIBLE)(extraInfoText setText text)
    def setExtraInfo(resource: Int) = wrap(extraInfo setVisibility View.VISIBLE)(extraInfoText setText resource)

    val wrappers =
      view.findViewById(R.id.refundableAmount).asInstanceOf[View] ::
        view.findViewById(R.id.paymentsInFlight).asInstanceOf[View] ::
        view.findViewById(R.id.totalPayments).asInstanceOf[View] ::
        view.findViewById(R.id.totalCapacity).asInstanceOf[View] ::
        view.findViewById(R.id.fundingDepth).asInstanceOf[View] ::
        view.findViewById(R.id.canReceive).asInstanceOf[View] ::
        view.findViewById(R.id.startedAt).asInstanceOf[View] ::
        view.findViewById(R.id.refundFee).asInstanceOf[View] ::
        view.findViewById(R.id.closedAt).asInstanceOf[View] ::
        view.findViewById(R.id.canSend).asInstanceOf[View] ::
        baseBar :: overBar :: Nil

    val totalPaymentsText = view.findViewById(R.id.totalPaymentsText).asInstanceOf[TextView]
    val refundableAmountText = view.findViewById(R.id.refundableAmountText).asInstanceOf[TextView]
    val paymentsInFlightText = view.findViewById(R.id.paymentsInFlightText).asInstanceOf[TextView]
    val totalCapacityText = view.findViewById(R.id.totalCapacityText).asInstanceOf[TextView]
    val fundingDepthText = view.findViewById(R.id.fundingDepthText).asInstanceOf[TextView]
    val canReceiveText = view.findViewById(R.id.canReceiveText).asInstanceOf[TextView]
    val startedAtText = view.findViewById(R.id.startedAtText).asInstanceOf[TextView]
    val refundFeeText = view.findViewById(R.id.refundFeeText).asInstanceOf[TextView]
    val closedAtText = view.findViewById(R.id.closedAtText).asInstanceOf[TextView]
    val canSendText = view.findViewById(R.id.canSendText).asInstanceOf[TextView]
    baseBar setMax 1000
    overBar setMax 1000
    view setTag this

    def visibleExcept(gone: Int*) =
      for (textWrapper <- wrappers) {
        val isGone = gone contains textWrapper.getId
        textWrapper setVisibility viewMap(!isGone)
      }

    def showDetails(chan: Channel, cs: Commitments) = {
      // Attempt to display relevant details based on state
      // fallback to generic details if state is not known

      val capacity = cs.commitInput.txOut.amount
      val started = me time new Date(cs.startedAt)
      val breakFee = Satoshi(cs.reducedRemoteState.myFeeSat)
      val canReceiveMsat = estimateCanReceive(chan)
      val canSendMsat = estimateCanSend(chan)

      val refundable = cs.localCommit.spec.toLocalMsat
      val inFlight = inFlightHtlcs(chan).toList.map(_.add.amountMsat).sum
      val barCanSend = cs.remoteCommit.spec.toRemoteMsat / capacity.amount
      val barCanReceive = barCanSend + canReceiveMsat / capacity.amount

      // For incoming chans reserveAndFee is reserve only since fee is zero
      val reserveAndFee = breakFee.amount + cs.remoteParams.channelReserveSatoshis
      val barLocalReserve = math.min(barCanSend, reserveAndFee * 1000L / capacity.amount)
      val fundingDepth \ fundingIsDead = LNParams.broadcaster.getStatus(chan.fundTxId)
      val threshold = math.max(cs.remoteParams.minimumDepth, LNParams.minDepth)

      baseBar setProgress barCanSend.toInt
      baseBar setSecondaryProgress barCanReceive.toInt
      overBar setProgress barLocalReserve.toInt

      startedAtText setText started.html
      totalPaymentsText setText getStat(cs.channelId).toString
      fundingDepthText setText getString(ln_info_funding).format(fundingDepth, threshold).html
      refundableAmountText setText denom.parsedWithSign(Satoshi(refundable) / 1000L).html
      canReceiveText setText denom.parsedWithSign(Satoshi(canReceiveMsat) / 1000L).html
      canSendText setText denom.parsedWithSign(Satoshi(canSendMsat) / 1000L).html
      paymentsInFlightText setText sumOrNothing(Satoshi(inFlight) / 1000L).html
      totalCapacityText setText denom.parsedWithSign(capacity).html
      refundFeeText setText sumOrNothing(breakFee).html

      chan.data match {
        case norm: NormalData if isOperational(chan) =>
          // Order matters: NormalData should always come before WaitData
          // we only can display one item so sort them by increasing importance

          channelAndHop(chan) match {
            case Some(_ \ vec) if isHighFee(vec) => setExtraInfo(me getString ln_info_high_fee format vec.head.feeBreakdown)
            case None if fundingDepth > 6 => setExtraInfo(resource = ln_info_no_receive)
            case _ => // We may not have it until 6 confs, do nothing
          }

          // In Turbo channels we will have an OPEN state with NormalData and zeroconf
          if (norm.unknownSpend.isDefined) setExtraInfo(resource = ln_info_unknown_spend)
          if (fundingIsDead) setExtraInfo(resource = ln_info_funding_lost)

          // Relevant for Turbo channels: show funding depth until it reaches threshold
          val fundingDepthCondition = if (fundingDepth < threshold) -1 else R.id.fundingDepth
          visibleExcept(gone = fundingDepthCondition, R.id.closedAt)

        case _: NormalData | _: NegotiationsData =>
          setExtraInfo(resource = ln_info_coop_attempt)
          visibleExcept(gone = R.id.baseBar, R.id.overBar, R.id.canSend,
            R.id.canReceive, R.id.refundFee, R.id.fundingDepth, R.id.closedAt)

        case wait: WaitData =>
          if (fundingIsDead) setExtraInfo(resource = ln_info_funding_lost)
          // Should catch WaitBroadcastRemoteData and WaitFundingDoneData, not NormalData
          visibleExcept(gone = R.id.baseBar, R.id.overBar, R.id.canSend, R.id.canReceive,
            R.id.closedAt, R.id.paymentsInFlight, R.id.totalPayments)

        case cd: ClosingData =>
          setExtraInfo(text = me closedBy cd)
          val closeDate = new Date(cd.closedAt)
          closedAtText setText time(closeDate).html

          // Show breaking fee if this is NOT a mutual closing
          val refundFeeCondition = if (cd.mutualClose.isEmpty) -1 else R.id.refundFee
          visibleExcept(gone = R.id.baseBar, R.id.overBar, R.id.canSend, R.id.canReceive,
            refundFeeCondition, R.id.fundingDepth, R.id.paymentsInFlight)

        case _ =>
          visibleExcept(gone = R.id.baseBar, R.id.overBar, R.id.canSend,
            R.id.canReceive, R.id.refundFee, R.id.closedAt, R.id.fundingDepth,
            R.id.paymentsInFlight, R.id.totalPayments)
      }

      // MENU PART

      def warnAndMaybeClose(channelClosureWarning: String) =
        mkCheckForm(alert => rm(alert)(chan process ChannelManager.CMDLocalShutdown),
          none, baseTextBuilder(channelClosureWarning.html), dialog_ok, dialog_cancel)

      view setOnClickListener onButtonTap {
        // All channel actions are sensitive, require auth
        fpAuth(new Goldfinger.Builder(me).build, none)(next)

        def next = {
          val contextualChannelMenu = chan.data match {
            // Unknown spend may be a future commit so we should not allow force-closing
            case norm: NormalData if norm.unknownSpend.isDefined => chanActions.patch(2, Nil, 2)
            // This likely means they have not broadcasted a tx, wait for it instead of closing
            case _: WaitBroadcastRemoteData => chanActions take 2
            case _: ClosingData => chanActions.patch(2, Nil, 1)
            // Should not allow force-closing with old commit
            case _: RefundingData => chanActions take 2
            // Cut out refunding tx option
            case _ => chanActions take 3
          }

          val lst = getLayoutInflater.inflate(R.layout.frag_center_list, null).asInstanceOf[ListView]
          val alert = showForm(negBuilder(dialog_cancel, chan.data.announce.asString.html, lst).create)
          lst setAdapter new ArrayAdapter(me, R.layout.frag_top_tip, R.id.titleTip, contextualChannelMenu)
          lst setDividerHeight 0
          lst setDivider null

          lst setOnItemClickListener onTap { pos =>
            // User has already authorized these actions
            // so display to action list right away here
            rm(alert)(defineAction)

            def defineAction = chan.data match {
              case _ if 0 == pos => urlIntent(txid = chan.fundTxId.toString)
              case _ if 1 == pos => share(chan.data.asInstanceOf[HasCommitments].toJson.toString)
              // In the following two cases channel menu is reduced by 2 so we need to show an appropriate closing tx here
              case norm: NormalData if 2 == pos && norm.unknownSpend.isDefined => urlIntent(txid = norm.unknownSpend.get.txid.toString)
              case closing: ClosingData if 2 == pos => urlIntent(txid = closing.bestClosing.commitTx.txid.toString)
              case _ =>
                val canCoopClose = isOpeningOrOperational(chan)
                val isBlockerPresent = inFlightHtlcs(chan).nonEmpty
                if (canCoopClose && isBlockerPresent) warnAndMaybeClose(me getString ln_chan_close_inflight_details)
                else if (canCoopClose) warnAndMaybeClose(me getString ln_chan_close_confirm_local)
                else warnAndMaybeClose(me getString ln_chan_force_details)
            }
          }
        }
      }
    }

    def fillView(chan: Channel) = {
      val state = stateStatusColor(chan)
      val connect = connectivityStatusColor(chan)

      extraInfo setVisibility View.GONE
      addressAndKey setText chan.data.announce.asString.html
      stateAndConnectivity setText s"<strong>$state</strong><br>$connect".html
      // Details method should be called after extraInfo is set to GONE above
      chan.hasCsOr(sm => showDetails(chan, sm.commitments), null)
    }
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.lnops, menu)
    true
  }

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionAddNodeId) me share LNParams.nodePublicKey.toString
  }

  override def onDestroy = wrap(super.onDestroy) {
    app.kit.peerGroup removeBlocksDownloadedEventListener eventsListener
    for (chan <- displayedChans) chan.listeners -= eventsListener
  }

  def INIT(s: Bundle) = if (app.isAlive) {
    me setContentView R.layout.activity_ln_ops
    me initToolbar findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    getSupportActionBar setSubtitle app.plur1OrZero(presentChans, displayedChans.size)
    getSupportActionBar setTitle action_ln_details

    gridView setAdapter adapter
    gridView setNumColumns math.round(scrWidth / 2.4).toInt
    app.kit.peerGroup addBlocksDownloadedEventListener eventsListener
    for (chan <- displayedChans) chan.listeners += eventsListener
  } else me exitTo classOf[MainActivity]

  // UTILS

  def stateStatusColor(c: Channel) = (c.data, c.state) match {
    case (_: NormalData, OPEN) if isOperational(c) => me getString ln_info_status_open
    case (_: NormalData, _) if !isOperational(c) => me getString ln_info_status_shutdown
    case (_, WAIT_FUNDING_DONE) => me getString ln_info_status_opening
    case (_, NEGOTIATIONS) => me getString ln_info_status_negotiations
    case _ => me getString ln_info_status_other format c.state
  }

  def connectivityStatusColor(c: Channel) =
    ConnectionManager.connections get c.data.announce.nodeId match {
      case Some(w) if w.socket.isConnected => me getString ln_info_state_online
      case _ => me getString ln_info_state_offline
    }

  def closedBy(cd: ClosingData) =
    if (cd.remoteCommit.nonEmpty) me getString ln_info_close_remote
    else if (cd.nextRemoteCommit.nonEmpty) me getString ln_info_close_remote
    else if (cd.mutualClose.nonEmpty) me getString ln_info_close_coop
    else me getString ln_info_close_local

  def urlIntent(txid: String) = host startActivity new Intent(Intent.ACTION_VIEW, Uri parse s"https://smartbit.com.au/tx/$txid")
  def canDisplay(some: ChannelData) = some match { case ref: RefundingData => ref.remoteLatestPoint.isDefined case _ => true }
  def sumOrNothing(sats: Satoshi) = if (0L == sats.toLong) getString(ln_info_nothing) else denom parsedWithSign sats
  def isHighFee(route: PaymentRoute) = LNParams.isFeeBreach(route, msat = 1000000000L)

  def getStat(chanId: ByteVector) = {
    val cursor = LNParams.db.select(PaymentTable.selectPaymentNumSql, chanId)
    RichCursor(cursor) headTry { case RichCursor(c1) => c1 getLong 0 } getOrElse 0L
  }
}