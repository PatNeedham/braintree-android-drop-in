package com.braintreepayments.api;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.IntDef;
import android.support.annotation.VisibleForTesting;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

import com.braintreepayments.api.dropin.R;
import com.braintreepayments.api.dropin.interfaces.AddPaymentUpdateListener;
import com.braintreepayments.api.dropin.view.AddCardView;
import com.braintreepayments.api.dropin.view.EditCardView;
import com.braintreepayments.api.dropin.view.EnrollmentCardView;
import com.braintreepayments.api.exceptions.AuthenticationException;
import com.braintreepayments.api.exceptions.AuthorizationException;
import com.braintreepayments.api.exceptions.ConfigurationException;
import com.braintreepayments.api.exceptions.DownForMaintenanceException;
import com.braintreepayments.api.exceptions.ErrorWithResponse;
import com.braintreepayments.api.exceptions.InvalidArgumentException;
import com.braintreepayments.api.exceptions.ServerException;
import com.braintreepayments.api.exceptions.UnexpectedException;
import com.braintreepayments.api.exceptions.UpgradeRequiredException;
import com.braintreepayments.api.interfaces.BraintreeErrorListener;
import com.braintreepayments.api.interfaces.ConfigurationListener;
import com.braintreepayments.api.interfaces.PaymentMethodNonceCreatedListener;
import com.braintreepayments.api.interfaces.UnionPayListener;
import com.braintreepayments.api.models.CardBuilder;
import com.braintreepayments.api.models.Configuration;
import com.braintreepayments.api.models.PaymentMethodNonce;
import com.braintreepayments.api.models.UnionPayCapabilities;
import com.braintreepayments.api.models.UnionPayCardBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.braintreepayments.api.BraintreePaymentActivity.BRAINTREE_RESULT_DEVELOPER_ERROR;
import static com.braintreepayments.api.BraintreePaymentActivity.BRAINTREE_RESULT_SERVER_ERROR;
import static com.braintreepayments.api.BraintreePaymentActivity.BRAINTREE_RESULT_SERVER_UNAVAILABLE;
import static com.braintreepayments.api.BraintreePaymentActivity.EXTRA_ERROR_MESSAGE;

public class AddCardActivity extends AppCompatActivity implements ConfigurationListener, AddPaymentUpdateListener,
        PaymentMethodNonceCreatedListener, BraintreeErrorListener, UnionPayListener {

    private static final String EXTRA_STATE = "com.braintreepayments.api.EXTRA_STATE";
    private static final String EXTRA_ENROLLMENT_ID = "com.braintreepayments.api.EXTRA_ENROLLMENT_ID";

    private String mEnrollmentId;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            LOADING,
            CARD_ENTRY,
            DETAILS_ENTRY,
            ENROLLMENT_ENTRY
    })
    private @interface State {}
    public static final int LOADING = 1;
    public static final int CARD_ENTRY = 2;
    public static final int DETAILS_ENTRY = 3;
    public static final int ENROLLMENT_ENTRY = 4;

    private ActionBar mActionBar;
    private ProgressBar mLoadingView;
    private AddCardView mAddCardView;
    private EditCardView mEditCardView;
    private EnrollmentCardView mEnrollmentCardView;

    private boolean mUnionPayCard;

    private BraintreeFragment mBraintreeFragment;

    @State
    private int mState = CARD_ENTRY;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bt_add_card_activity);

        mLoadingView = (ProgressBar) findViewById(R.id.bt_progress_bar);
        mAddCardView = (AddCardView) findViewById(R.id.bt_add_card_view);
        mEditCardView = (EditCardView) findViewById(R.id.bt_edit_card_view);
        mEnrollmentCardView = (EnrollmentCardView) findViewById(R.id.bt_enrollment_card_view);
        mEnrollmentCardView.setup(this);

        setSupportActionBar((Toolbar) findViewById(R.id.bt_toolbar));
        mActionBar = getSupportActionBar();
        mActionBar.setDisplayHomeAsUpEnabled(true);
        mAddCardView.setAddPaymentUpdatedListener(this);
        mEditCardView.setAddPaymentUpdatedListener(this);
        mEnrollmentCardView.setAddPaymentUpdatedListener(this);

        if (savedInstanceState != null) {
            //TODO is there a better way to respect this State interface?
            @State int state = savedInstanceState.getInt(EXTRA_STATE);
            mState = state;
            mEnrollmentId = savedInstanceState.getString(EXTRA_ENROLLMENT_ID);
        } else {
            mState = CARD_ENTRY;
        }

        enterState(LOADING);

        try {
            mBraintreeFragment = getBraintreeFragment();
        } catch (InvalidArgumentException e) {
            Intent intent = new Intent()
                    .putExtra(EXTRA_ERROR_MESSAGE, e.getMessage());
            setResult(BRAINTREE_RESULT_DEVELOPER_ERROR, intent);
            finish();
            return;
        }
    }

    @VisibleForTesting
    protected BraintreeFragment getBraintreeFragment() throws InvalidArgumentException {
        PaymentRequest paymentRequest = getIntent().getParcelableExtra(PaymentRequest.EXTRA_CHECKOUT_REQUEST);
        if (TextUtils.isEmpty(paymentRequest.getAuthorization())) {
            throw new InvalidArgumentException("A client token or client key must be specified " +
                    "in the " + PaymentRequest.class.getSimpleName());
        }

        return BraintreeFragment.newInstance(this, paymentRequest.getAuthorization());
    }

    @Override
    public void onConfigurationFetched(Configuration configuration) {
        mAddCardView.setup(this, configuration);
        mEditCardView.setup(this, configuration);

        setState(LOADING, mState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_STATE, mState);
        outState.putString(EXTRA_ENROLLMENT_ID, mEnrollmentId);
    }

    @Override
    public void onPaymentUpdated(View v) {
        setState(mState, determineNextState(v));
    }

    private void setState(int currentState, int nextState) {
        if (currentState == nextState) {
            return;
        }

        leaveState(currentState);
        enterState(nextState);

        mState = nextState;
    }

    private void leaveState(int state) {
        switch (state) {
            case LOADING:
                mLoadingView.setVisibility(GONE);
                break;
            case CARD_ENTRY:
                mAddCardView.setVisibility(GONE);
                break;
            case DETAILS_ENTRY:
                mEditCardView.setVisibility(GONE);
                break;
            case ENROLLMENT_ENTRY:
                mEnrollmentCardView.setVisibility(GONE);
                break;
        }
    }

    private void enterState(int state) {
        switch(state) {
            case LOADING:
                mActionBar.setTitle(R.string.bt_card_details);
                mLoadingView.setVisibility(VISIBLE);
                break;
            case CARD_ENTRY:
                mActionBar.setTitle(R.string.bt_card_details);
                mAddCardView.setVisibility(VISIBLE);
                break;
            case DETAILS_ENTRY:
                mActionBar.setTitle(R.string.bt_card_details);
                mEditCardView.setCardNumber(mAddCardView.getCardForm().getCardNumber());
                mEditCardView.useUnionPay(this, mUnionPayCard);
                mEditCardView.setVisibility(VISIBLE);
                break;
            case ENROLLMENT_ENTRY:
                mActionBar.setTitle(R.string.bt_confirm_enrollment);
                mEnrollmentCardView.setPhoneNumber(
                        PhoneNumberUtils.formatNumber(mEditCardView.getCardForm().getCountryCode() +
                                mEditCardView.getCardForm().getMobileNumber()));
                mEnrollmentCardView.setVisibility(VISIBLE);
                break;
        }
    }

    @Override
    public void onBackRequested(View v) {
        if (v.getId() == mEditCardView.getId()) {
            setState(DETAILS_ENTRY, CARD_ENTRY);
        } else if (v.getId() == mEnrollmentCardView.getId()) {
            setState(ENROLLMENT_ENTRY, DETAILS_ENTRY);
        }
    }

    @State
    private int determineNextState(View v) {
        int nextState = mState;
        if (v.getId() == mAddCardView.getId() && !TextUtils.isEmpty(mAddCardView.getCardForm().getCardNumber())) {
            if (!mBraintreeFragment.getConfiguration().getUnionPay().isEnabled()) {
                mEditCardView.useUnionPay(this, false);
                nextState = DETAILS_ENTRY;
            } else {
                UnionPay.fetchCapabilities(mBraintreeFragment, mAddCardView.getCardForm().getCardNumber());
            }
        } else if (v.getId() == mEditCardView.getId()) {
            if (mUnionPayCard) {
                if (TextUtils.isEmpty(mEnrollmentId)) {
                    UnionPayCardBuilder unionPayCardBuilder = new UnionPayCardBuilder()
                            .cardNumber(mEditCardView.getCardForm().getCardNumber())
                            .expirationMonth(mEditCardView.getCardForm().getExpirationMonth())
                            .expirationYear(mEditCardView.getCardForm().getExpirationYear())
                            .cvv(mEditCardView.getCardForm().getCvv())
                            .postalCode(mEditCardView.getCardForm().getPostalCode())
                            .mobileCountryCode(mEditCardView.getCardForm().getCountryCode())
                            .mobilePhoneNumber(mEditCardView.getCardForm().getMobileNumber());

                    UnionPay.enroll(mBraintreeFragment, unionPayCardBuilder);
                } else {
                    nextState = ENROLLMENT_ENTRY;
                }
            } else {
                nextState = mState;
                createCard();
            }
        } else if (v.getId() == mEnrollmentCardView.getId()) {
            nextState = mState;
            createCard();
        }

        return nextState;
    }

    private void createCard() {
        if (mUnionPayCard) {
            UnionPayCardBuilder unionPayCardBuilder = new UnionPayCardBuilder()
                    .cardNumber(mEditCardView.getCardForm().getCardNumber())
                    .expirationMonth(mEditCardView.getCardForm().getExpirationMonth())
                    .expirationYear(mEditCardView.getCardForm().getExpirationYear())
                    .cvv(mEditCardView.getCardForm().getCvv())
                    .postalCode(mEditCardView.getCardForm().getPostalCode())
                    .mobileCountryCode(mEditCardView.getCardForm().getCountryCode())
                    .mobilePhoneNumber(mEditCardView.getCardForm().getMobileNumber())
                    .enrollmentId(mEnrollmentId)
                    .smsCode(mEnrollmentCardView.getSmsCode());

            UnionPay.tokenize(mBraintreeFragment, unionPayCardBuilder);
        } else {
            CardBuilder cardBuilder = new CardBuilder()
                    .cardNumber(mEditCardView.getCardForm().getCardNumber())
                    .expirationMonth(mEditCardView.getCardForm().getExpirationMonth())
                    .expirationYear(mEditCardView.getCardForm().getExpirationYear())
                    .cvv(mEditCardView.getCardForm().getCvv())
                    .postalCode(mEditCardView.getCardForm().getPostalCode());

            Card.tokenize(mBraintreeFragment, cardBuilder);
        }
    }

    @Override
    public void onPaymentMethodNonceCreated(PaymentMethodNonce paymentMethod) {
        Intent result = new Intent();
        result.putExtra(BraintreePaymentActivity.EXTRA_PAYMENT_METHOD_NONCE, paymentMethod);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    @Override
    public void onCapabilitiesFetched(UnionPayCapabilities capabilities) {
        mUnionPayCard = capabilities.isUnionPay();

        if (mUnionPayCard && !capabilities.isSupported()) {
            mAddCardView.showCardNotSupportedError();
        } else {
            setState(mState, DETAILS_ENTRY);
        }
    }

    @Override
    public void onSmsCodeSent(String enrollmentId, boolean smsRequired) {
        mEnrollmentId = enrollmentId;
        mEditCardView.useUnionPay(this, smsRequired);
        onPaymentUpdated(mEditCardView);
    }

    @Override
    public void onError(Exception error) {
        if (error instanceof ErrorWithResponse) {
            setState(mState, DETAILS_ENTRY);
            mEditCardView.setErrors((ErrorWithResponse) error);
        } else {
            if (error instanceof AuthenticationException || error instanceof AuthorizationException ||
                    error instanceof UpgradeRequiredException) {
                mBraintreeFragment.sendAnalyticsEvent("sdk.exit.developer-error");
                setResult(BRAINTREE_RESULT_DEVELOPER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
            } else if (error instanceof ConfigurationException) {
                mBraintreeFragment.sendAnalyticsEvent("sdk.exit.configuration-exception");
                setResult(BRAINTREE_RESULT_SERVER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
            } else if (error instanceof ServerException || error instanceof UnexpectedException) {
                mBraintreeFragment.sendAnalyticsEvent("sdk.exit.server-error");
                setResult(BRAINTREE_RESULT_SERVER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
            } else if (error instanceof DownForMaintenanceException) {
                mBraintreeFragment.sendAnalyticsEvent("sdk.exit.server-unavailable");
                setResult(BRAINTREE_RESULT_SERVER_UNAVAILABLE, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
            }
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        if (mAddCardView.getCardForm().isCardScanningAvailable()) {
            getMenuInflater().inflate(R.menu.bt_card_io, menu);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.bt_card_io_button) {
            mAddCardView.getCardForm().scanCard(this);
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
