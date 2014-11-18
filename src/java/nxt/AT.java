/*
 * Some portion .. Copyright (c) 2014 CIYAM Developers

 Distributed under the MIT/X11 software license, please refer to the file license.txt
 in the root project directory or http://www.opensource.org/licenses/mit-license.php.

 */

package nxt;


import nxt.at.AT_API_Helper;
import nxt.at.AT_Constants;
import nxt.at.AT_Controller;
import nxt.at.AT_Exception;
import nxt.at.AT_Machine_State;
import nxt.at.AT_Transaction;
import nxt.db.Db;
import nxt.db.DbKey;
import nxt.db.DbUtils;
import nxt.db.VersionedEntityDbTable;
import nxt.util.Convert;
import nxt.util.Listener;
import nxt.Account;
import nxt.TransactionImpl.BuilderImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class AT extends AT_Machine_State {

	static {
		Nxt.getBlockchainProcessor().addListener(new Listener<Block>() {
			@Override
			public void notify(Block block) {
				try {
					if (block.getBlockATs()!=null)
					{
						LinkedHashMap<byte[],byte[]> blockATs = AT_Controller.getATsFromBlock(block.getBlockATs());
						for ( byte[] id : blockATs.keySet()){
							Long atId = AT_API_Helper.getLong( id );
							AT at = AT.getAT( atId );

							Account senderAccount = Account.getAccount( atId );
							Long fees = at.getMachineState().getSteps() * AT_Constants.getInstance().STEP_FEE( block.getHeight() );

							if ( !( senderAccount.getUnconfirmedBalanceNQT() < fees ) )
							{
								senderAccount.addToUnconfirmedBalanceNQT( -fees );
								senderAccount.addToBalanceNQT( -fees );
								makeTransactions( at , block );
							}


						}
					}
				} catch (AT_Exception e) {
					e.printStackTrace();
				}	

			}

			private void makeTransactions( AT at, Block block )
			{
				try (Connection con = Db.getConnection()) {
					TransactionDb.saveTransactions(con , at , block );
				} catch (SQLException e) {
					throw new RuntimeException(e.toString(), e);
				}
			}

		}, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);
	}    

	public static class ATState {

		private final long atId;
		private final DbKey dbKey;
		private byte[] state;
		private int prevHeight;
		private int nextHeight;

		private ATState(long atId, byte[] state , int prevHeight , int nextHeight) {
			this.atId = atId;
			this.dbKey = atStateDbKeyFactory.newKey(this.atId);
			this.state = state;
			this.nextHeight = nextHeight;
		}

		private ATState(ResultSet rs) throws SQLException {
			this.atId = rs.getLong("at_id");
			this.dbKey = atStateDbKeyFactory.newKey(this.atId);
			this.state = rs.getBytes("state");
			this.prevHeight = rs.getInt("prev_height");
			this.nextHeight = rs.getInt("next_height");
		}

		private void save(Connection con) throws SQLException {
			try (PreparedStatement pstmt = con.prepareStatement("MERGE INTO at_state (at_id, "
					+ "state, prev_height ,next_height, height, latest) KEY (at_id) VALUES (?, ?, ?, ?, ?, TRUE)")) {
				int i = 0;
				pstmt.setLong(++i, atId);
				DbUtils.setBytes(pstmt, ++i, state);
				pstmt.setInt( ++i , prevHeight);
				pstmt.setInt(++i, nextHeight);
				pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
				pstmt.executeUpdate();
			}
		}

		public long getATId() {
			return atId;
		}

		public byte[] getState() {
			return state;
		}
		
		public int getPrevHeight() {
			return prevHeight;
		}

		public int getNextHeight() {
			return nextHeight;
		}

		public void setState(byte[] newState) {
			state = newState;
		}
		
		public void setPrevHeight(int prevHeight){
			this.prevHeight = prevHeight; 
		}
		
		public void setNextHeight(int newNextHeight) {
			nextHeight = newNextHeight;
		}
	}

	private static final DbKey.LongKeyFactory<AT> atDbKeyFactory = new DbKey.LongKeyFactory<AT>("id") {
		@Override
		public DbKey newKey(AT at) {
			return at.dbKey;
		}
	};
	
	private static final VersionedEntityDbTable<AT> atTable = new VersionedEntityDbTable<AT>("at", atDbKeyFactory) {
		@Override
		protected AT load(Connection con, ResultSet rs) throws SQLException {
			//return new AT(rs);
			throw new RuntimeException("AT attempted to be created with atTable.load");
		}
		@Override
		protected void save(Connection con, AT at) throws SQLException {
			at.saveAT();
		}
		@Override
		protected String defaultSort() {
			return " ORDER BY id ";
		}
	};

	private static final DbKey.LongKeyFactory<ATState> atStateDbKeyFactory = new DbKey.LongKeyFactory<AT.ATState>("at_id") {
		@Override
		public DbKey newKey(ATState atState) {
			return atState.dbKey;
		}
	};

	private static final VersionedEntityDbTable<ATState> atStateTable = new VersionedEntityDbTable<ATState>("at_state", atStateDbKeyFactory) {
		@Override
		protected ATState load(Connection con, ResultSet rs) throws SQLException {
			return new ATState(rs);
		}
		@Override
		protected void save(Connection con, ATState atState) throws SQLException {
			atState.save(con);
		}
		@Override
		protected String defaultSort() {
			return " ORDER BY prev_height, height, at_id ";
		}
	};

	
	public static Collection<AT> getAllATs() 
	{
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT atId FROM at WHERE latest = TRUE" ) )
		{
			ResultSet result = pstmt.executeQuery();
			return createATs( result );
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	public static AT getAT(byte[] id) 
	{
		return getAT( AT_API_Helper.getLong( id ) );
	}

	public static AT getAT(Long id) {
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT a.id , a.creator_id , a.name , a.description , a.version , "
				+ "s.state , a.csize , a.dsize , a.c_user_stack_bytes , a.c_call_stack_bytes , "
				+ "a.minimum_fee , a.creation_height , a.sleep_between , a.freeze_when_same_balance , "
				+ "a.ap_code, s.prev_height, s.next_height FROM at a, at_state s WHERE a.id = ? AND a.latest = TRUE AND s.at_id = ? AND s.latest = TRUE" ) )
		{
			int i = 0;
			pstmt.setLong( ++i ,  id );
			pstmt.setLong( ++i , id );
			ResultSet result = pstmt.executeQuery();
			if ( result.next() )
			{
				return createATs( result ).get( 0 );
			}
			return null;
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	public static List<AT> getATsIssuedBy(Long accountId) {
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT a.id , a.creator , a.name , a.description , a.version , "
				+ "s.stateBytes , a.csize , a.dsize , a.c_user_stack_bytes , a.c_call_stack_bytes , "
				+ "a.minimum_fee , a.creation_height , a.sleep_between , a.freeze_when_same_balance , "
				+ "a.ap_code, s.prev_height, s.next_height FROM at a, at_state s WHERE a.id = s.at_id AND a.latest = true AND s.latest = TRUE and creator = ?") )
		{
			pstmt.setLong(1, accountId);
			ResultSet result = pstmt.executeQuery();
			return createATs( result );
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	static void addAT(Long atId, Long senderAccountId, String name, String description, byte[] creationBytes , int height) {

		ByteBuffer bf = ByteBuffer.allocate( 8 + 8 );
		bf.order( ByteOrder.LITTLE_ENDIAN );

		bf.putLong( atId );

		byte[] id = new byte[ 8 ];

		bf.putLong( 8 , senderAccountId );

		byte[] creator = new byte[ 8 ];
		bf.clear();
		bf.get( id , 0 , 8 );
		bf.get( creator , 0 , 8);

		AT at = new AT( id , creator , name , description , creationBytes , height );

		AT_Controller.resetMachine(at);

		at.saveAT( );
		
		at.saveState();

	}

	public void saveState() {
		ATState state = atStateTable.get(atStateDbKeyFactory.newKey( AT_API_Helper.getLong( this.getId() ) ) );
		int nextHeight = Nxt.getBlockchain().getHeight() + getWaitForNumberOfBlocks();
		if(state != null) {
			state.setState(getState());
			state.setPrevHeight( Nxt.getBlockchain().getHeight() );
			state.setNextHeight(nextHeight);
		}
		else {
			state = new ATState( AT_API_Helper.getLong( this.getId() ) , getState(), Nxt.getBlockchain().getHeight(), nextHeight);
		}
		atStateTable.insert(state);
	}

	private static List<AT> createATs( ResultSet rs ) throws SQLException
	{
		List<AT> ats = new ArrayList<AT>();
		while ( rs.next() )
		{
			int i = 0;
			Long atId = rs.getLong( ++i );
			Long creator = rs.getLong( ++i );
			String name = rs.getString( ++i );
			String description = rs.getString( ++i );
			short version = rs.getShort( ++i );
			byte[] stateBytes = rs.getBytes( ++i );
			int csize = rs.getInt( ++i );
			int dsize = rs.getInt( ++i );
			int c_user_stack_bytes = rs.getInt( ++i );
			int c_call_stack_bytes = rs.getInt( ++i );
			long minimumFee = rs.getLong( ++i );
			int creationBlockHeight = rs.getInt( ++i );
			int sleepBetween = rs.getInt( ++i );
			boolean freezeWhenSameBalance = rs.getBoolean( ++i );
			byte[] ap_code = rs.getBytes( ++i );
			int prevHeight = rs.getInt(++i);
			int nextHeight =rs.getInt(++i);

			AT at = new AT( AT_API_Helper.getByteArray( atId ) , AT_API_Helper.getByteArray( creator ) , name , description , version ,
					stateBytes , csize , dsize , c_user_stack_bytes , c_call_stack_bytes , minimumFee , creationBlockHeight , sleepBetween , 
					freezeWhenSameBalance , ap_code, prevHeight, nextHeight );
			ats.add( at );

		}
		return ats;
	}

	private void saveAT( )
	{
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "INSERT INTO at " 
				+ "(id , creator_id , name , description , version , "
				+ "csize , dsize , c_user_stack_bytes , c_call_stack_bytes , "
				+ "minimum_fee , creation_height , sleep_between, freeze_when_same_balance , "
				+ "ap_code , height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" ) )
				{
			int i = 0;
			pstmt.setLong( ++i , AT_API_Helper.getLong( this.getId() ) );
			pstmt.setLong( ++i, AT_API_Helper.getLong( this.getCreator() ) );
			DbUtils.setString( pstmt , ++i , this.getName() );
			DbUtils.setString( pstmt , ++i , this.getDescription() );
			pstmt.setShort( ++i , this.getVersion() );
			pstmt.setInt( ++i , this.getCsize() );
			pstmt.setInt( ++i , this.getDsize() );
			pstmt.setInt( ++i , this.getC_user_stack_bytes() );
			pstmt.setInt( ++i , this.getC_call_stack_bytes() );
			pstmt.setLong( ++i , this.getMinimumFee() );
			pstmt.setInt( ++i, this.getCreationBlockHeight() );
			pstmt.setInt(++i, this.getSleepBetween());
			pstmt.setBoolean( ++i , this.freezeOnSameBalance() );
			DbUtils.setBytes( pstmt , ++i , this.getApCode() );
			pstmt.setInt( ++i , Nxt.getBlockchain().getHeight() );

			pstmt.executeUpdate();
				}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}

	}

	private static void deleteAT( AT at )
	{
		ATState atState = atStateTable.get(atStateDbKeyFactory.newKey(AT_API_Helper.getLong(at.getId())));
		if(atState != null) {
			atStateTable.delete(atState);
		}
		atTable.delete(at);
		
	}

	private static void deleteAT( Long id )
	{
		AT at = atTable.get(atDbKeyFactory.newKey(id));
		if(at != null) {
			deleteAT(at);
		}
		
	}

	public static List< Long > getOrderedATs(){
		List< Long > orderedATs = new ArrayList<>();
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT at_id from at_state WHERE next_height <= ? ORDER BY prev_height, next_height asc" ) )
		{
			pstmt.setInt( 1 ,  Nxt.getBlockchain().getHeight() );
			ResultSet result = pstmt.executeQuery();
			while ( result.next() )
			{
				Long id = result.getLong( 1 );
				orderedATs.add( id );
			}
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
		return orderedATs;
	}


	static boolean isATAccountId(Long id) {
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT id FROM at WHERE id = ? AND latest = TRUE" ) )
		{
			pstmt.setLong(1, id);
			ResultSet result = pstmt.executeQuery();
			return result.next();
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	private final String name;    
	private final String description;
	private int previousBlock;
	private final DbKey dbKey;


	private AT( byte[] atId , byte[] creator , String name , String description , byte[] creationBytes , int height ) {
		super( atId , creator , creationBytes , height );
		this.name = name;
		this.description = description;
		this.previousBlock = 0;
		dbKey = atDbKeyFactory.newKey(AT_API_Helper.getLong(atId));
	}

	public AT ( byte[] atId , byte[] creator , String name , String description , short version ,
			byte[] stateBytes, int csize , int dsize , int c_user_stack_bytes , int c_call_stack_bytes ,
			long minimumFee , int creationBlockHeight, int sleepBetween , 
			boolean freezeWhenSameBalance, byte[] apCode, int prevHeight, int nextHeight )
	{
		super( 	atId , creator , version ,
				stateBytes , csize , dsize , c_user_stack_bytes , c_call_stack_bytes ,
				minimumFee , creationBlockHeight , sleepBetween , 
				freezeWhenSameBalance , apCode );
		this.name = name;
		this.description = description;
		this.previousBlock = prevHeight;
		dbKey = atDbKeyFactory.newKey(AT_API_Helper.getLong(atId));
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public byte[] getApCode() {
		return getAp_code().array();
	}

	public byte[] getApData() {
		return getAp_data().array();
	}

}
